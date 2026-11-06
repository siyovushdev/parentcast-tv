package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.media.OnlineMediaIds
import dev.siyovush.parentcast.tv.playback.PlaybackCoordinator
import dev.siyovush.parentcast.tv.queue.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Metadata is asynchronous. A durable apply journal separates queue commit, preparation and PLAY.
 * A concurrent STOP or edit during preparation cancels the pending PLAY, never the user's intent. */
internal class OnlineVideoApi(
    private val coordinator:PlaybackCoordinator,
    private val dispatcher:CallDispatcher,
    private val scope:CoroutineScope,
    private val journal:File,
    private val previewSource:suspend (String)->JSONObject,
    private val startSaving:suspend (String,String,String,Long,Boolean)->Unit,
    private val displayReady:()->Boolean,
    private val remember:(String,JSONObject)->Unit = {_,_->},
    private val now:()->Long = System::currentTimeMillis,
    private val startOnline:suspend (String,String)->Unit = {_,_->error("ONLINE_UNAVAILABLE")},
) {
    private data class Preview(val id:String,val owner:String,val videoId:String,val created:Long,
        var status:String="LOADING",var data:JSONObject?=null,var error:String?=null,var job:Job?=null)
    private val previews=linkedMapOf<String,Preview>()
    private val records=linkedMapOf<String,JSONObject>()
    private var healthy=true
    init { if(journal.isFile)try {
        val root=JSONObject(journal.readText());require(root.getInt("version")==1)
        val array=root.getJSONArray("commands");require(array.length()<=500)
        repeat(array.length()){val record=array.getJSONObject(it);records[record.getString("key")]=record}
    }catch(_:Exception){healthy=false} }

    fun handles(path:String)=path in setOf("/api/video/preview","/api/video/cancel","/api/video/apply")
    @Synchronized fun containsVideo(owner:String,id:String):Boolean=previews.values.any { it.owner==owner && it.videoId==id && now()-it.created<900_000 && it.status !in setOf("CANCELLED","ERROR") }
    @Synchronized fun read(path:String,owner:String):ApiResponse? {
        if(!path.startsWith("/api/video/preview/"))return null
        val entry=previews[path.removePrefix("/api/video/preview/")]?.takeIf {it.owner==owner && now()-it.created<900_000}
            ?: return error(404,"PREVIEW_EXPIRED")
        return ApiResponse(200,JSONObject().put("preview",view(entry)).toString())
    }
    fun mutate(path:String,owner:String,json:JSONObject,key:String,fingerprint:String):ApiResponse = try {
        when(path){
            "/api/video/preview"->begin(owner,json.getString("url"))
            "/api/video/cancel"->cancel(owner,json.getString("previewId"))
            "/api/video/apply"->apply(owner,json,key,fingerprint)
            else->error(404,"NOT_FOUND")
        }
    }catch(_:IllegalArgumentException){error(400,"BAD_REQUEST")}
     catch(_:org.json.JSONException){error(400,"BAD_REQUEST")}
     catch(_:Exception){error(503,"VIDEO_OPERATION_FAILED")}

    @Synchronized private fun begin(owner:String,url:String):ApiResponse {
        val id=OnlineMediaIds.parseLink(url)
        previews.values.filter {now()-it.created>=900_000}.forEach {it.job?.cancel();previews.remove(it.id)}
        if(previews.values.count {it.owner==owner && it.status=="LOADING"}>=2 || previews.size>=32)return error(429,"PREVIEW_LIMIT")
        val entry=Preview(UUID.randomUUID().toString(),owner,id,now());previews[entry.id]=entry
        entry.job=scope.launch(Dispatchers.IO) {
            try {
                val data=withTimeout(120_000){previewSource(id)}
                require(data.getString("videoId")==id)
                val safe=JSONObject().put("title",cleanVideoTitle(data.optString("title")))
                    .put("durationSeconds",data.optLong("durationSeconds").takeIf {it>0} ?: JSONObject.NULL)
                    .put("qualities",JSONArray().apply {
                        val choices=data.getJSONArray("qualities");require(choices.length()<=20)
                        repeat(choices.length()){n->val choice=choices.getJSONObject(n);val height=choice.getInt("height");val choiceId=choice.getString("id")
                            require(height in 1..1080 && choiceId.matches(Regex("[a-f0-9]{64}")))
                            put(JSONObject().put("id",choiceId).put("height",height).put("sizeBytes",choice.optLong("sizeBytes").takeIf {it>0} ?: JSONObject.NULL)) }
                    })
                synchronized(this@OnlineVideoApi){if(entry.status=="LOADING"){entry.data=safe;entry.status="COMPLETE";remember(id,data)}}
            }catch(e:CancellationException){synchronized(this@OnlineVideoApi){if(entry.status=="LOADING"){entry.status=if(e is TimeoutCancellationException)"ERROR" else "CANCELLED";entry.error=if(e is TimeoutCancellationException)"PREVIEW_TIMEOUT" else null}}}
             catch(e:Exception){synchronized(this@OnlineVideoApi){if(entry.status=="LOADING"){entry.status="ERROR";entry.error=safeCode(e,"VIDEO_PREVIEW_FAILED")}}}
        }
        return ApiResponse(202,JSONObject().put("preview",view(entry)).toString())
    }
    @Synchronized private fun cancel(owner:String,id:String):ApiResponse {
        val entry=previews[id]?.takeIf {it.owner==owner} ?: return error(404,"PREVIEW_EXPIRED")
        entry.status="CANCELLED";entry.job?.cancel();return ApiResponse(200,JSONObject().put("preview",view(entry)).toString())
    }
    private fun view(entry:Preview)=JSONObject(entry.data?.toString() ?: "{}").put("previewId",entry.id).put("videoId",entry.videoId).put("status",entry.status).put("error",entry.error ?: JSONObject.NULL)

    private fun apply(owner:String,json:JSONObject,key:String,fingerprint:String):ApiResponse {
        if(!healthy)return error(503,"COMMAND_STORAGE_ERROR")
        var record=synchronized(this){records[key]?.let {JSONObject(it.toString())}}
        if(record!=null && record.getString("fingerprint")!=fingerprint)return error(409,"IDEMPOTENCY_KEY_REUSED")
        if(record?.optBoolean("done")==true)return result(record,true)
        if(record==null){
            val p=synchronized(this){previews[json.getString("previewId")]?.takeIf {it.owner==owner && it.status=="COMPLETE" && now()-it.created<900_000}}
                ?: return error(409,"PREVIEW_EXPIRED")
            val action=json.getString("action");require(action in setOf("ADD","DOWNLOAD_LATER","WATCH_SAVE","WATCH_ONLINE"))
            val version=integer(json,"queueVersion");require(version>=0)
            val quality=if(action=="ADD")"" else json.getString("qualityId")
            if(action!="ADD")require(p.data!!.getJSONArray("qualities").let {a->(0 until a.length()).any {a.getJSONObject(it).getString("id")==quality}})
            if(action in setOf("WATCH_SAVE","WATCH_ONLINE") && !displayReady())return error(409,"OPEN_PLAYER_ON_TV")
            val groupId=json.optString("groupId").takeIf {it.isNotBlank() && it!="null"}
            val groupName=json.optString("groupName").takeIf {it.isNotBlank()}
            require(groupId==null || groupName==null)
            record=JSONObject().put("key",key).put("fingerprint",fingerprint).put("owner",owner).put("videoId",p.videoId)
                .put("title",p.data!!.optString("title")).put("action",action).put("qualityId",quality).put("expectedVersion",version)
                .put("storageId",if(action in setOf("ADD","WATCH_ONLINE"))"" else json.getString("storageId"))
                .put("storageVersion",if(action in setOf("ADD","WATCH_ONLINE"))0 else integer(json,"storageVersion"))
                .put("groupId",groupId ?: JSONObject.NULL).put("groupName",groupName ?: JSONObject.NULL)
                .put("created",now()).put("addedVersion",-1).put("done",false)
            saveRecord(record)
        }
        val command=record
        val videoId=command.getString("videoId");val action=command.getString("action")
        val playKey="$key:play";val addKey="$key:add"
        if(dispatcher.call {coordinator.batchReplay(playKey,fingerprint)}==BatchAddResult.REPLAY){
            command.put("done",true).put("stage","PLAYING");saveRecord(command);return result(command,true)
        }
        if(command.getLong("addedVersion")<0){
            val response=dispatcher.call {
                if(coordinator.batchReplay(addKey,fingerprint)==BatchAddResult.REPLAY)return@call null
                val q=coordinator.currentSnapshot().queueState
                val targetId=command.optString("groupId").takeUnless {it.isBlank()||it=="null"}
                val name=command.optString("groupName").takeUnless {it.isBlank()||it=="null"}
                val target=targetId?.let {id->q.groups.find {it.id==id} ?: throw IllegalArgumentException("GROUP_NOT_FOUND")}
                val group=target?.copy(videoIds=(target.videoIds+videoId).distinct()) ?: name?.let {QueueGroup.fixed(key,it,listOf(videoId),QueueGroupSource.CUSTOM)}
                val added=coordinator.addBatch(listOf(videoId),command.getLong("expectedVersion"),addKey,fingerprint,group=group,
                    titles=mapOf(videoId to cleanVideoTitle(command.getString("title"))))
                Triple(added,coordinator.currentSnapshot().queueState.version,group?.id)
            }
            // A process died after queue commit but before the journal update. Never guess whether
            // the parent subsequently changed playback; present the saved video for explicit action.
            if(response==null){command.put("done",true).put("stage","SAVED").put("error","SAVED_REVIEW_REQUIRED");saveRecord(command);return result(command,true)}
            if(response.first !in setOf(BatchAddResult.APPLIED,BatchAddResult.REPLAY))return batchError(response.first)
            command.put("addedVersion",response.second).put("groupId",response.third ?: JSONObject.NULL).put("stage","SAVED");saveRecord(command)
        }
        if(action!="ADD")try {
            runBlocking(Dispatchers.IO){
                if(action=="WATCH_ONLINE")startOnline(videoId,command.getString("qualityId"))
                else startSaving(videoId,command.getString("qualityId"),command.getString("storageId"),command.getLong("storageVersion"),action=="WATCH_SAVE")
            }
            command.put("stage",if(action=="WATCH_ONLINE")"ONLINE_READY" else "PREPARING")
        }catch(e:Exception){
            // Queue commit is real and visible. A retry with the same command resumes preparation.
            command.put("error",safeCode(e,"PREPARATION_FAILED"));saveRecord(command)
            return ApiResponse(503,resultJson(command).toString())
        }
        command.remove("error")
        if(action in setOf("WATCH_SAVE","WATCH_ONLINE")){
            val selected=dispatcher.call {
                val q=coordinator.currentSnapshot().queueState
                val item=q.items.find {it.videoId==videoId} ?: return@call BatchAddResult.CONFLICT
                if(!displayReady())return@call BatchAddResult.CONFLICT
                val position=q.progress[videoId]?.takeUnless {it.status==VideoWatchStatus.WATCHED}?.positionMs ?: 0L
                coordinator.selectQueued(item.queueItemId,command.getLong("addedVersion"),position,true,playKey,fingerprint,
                    command.optString("groupId").takeUnless {it.isBlank()||it=="null"})
            }
            if(selected !in setOf(BatchAddResult.APPLIED,BatchAddResult.REPLAY))command.put("error","PLAYBACK_CHANGED")
            else command.put("stage","PLAYING")
        }
        command.put("done",true);saveRecord(command);return result(command,false)
    }
    @Synchronized private fun saveRecord(record:JSONObject){
        check(healthy)
        val kept=records.filterValues {now()-it.optLong("created")<86_400_000}.toMutableMap()
        require(kept.size<500 || kept.containsKey(record.getString("key")))
        kept[record.getString("key")]=JSONObject(record.toString())
        journal.parentFile?.mkdirs();val temp=File(journal.parentFile,journal.name+".tmp")
        try {
            FileOutputStream(temp).use {it.write(JSONObject().put("version",1).put("commands",JSONArray(kept.values.toList())).toString().toByteArray());it.fd.sync()}
            Files.move(temp.toPath(),journal.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            records.clear();records.putAll(kept)
        } finally {temp.delete()}
    }
    private fun resultJson(r:JSONObject)=JSONObject().put("videoId",r.getString("videoId")).put("groupId",r.opt("groupId") ?: JSONObject.NULL)
        .put("queueVersion",dispatcher.call {coordinator.currentSnapshot().queueState.version}).put("stage",r.optString("stage","SAVED"))
        .put("error",r.opt("error") ?: JSONObject.NULL).put("mode",if(r.optString("action")=="WATCH_ONLINE")"ONLINE" else if(r.optString("action")=="ADD")"ADD" else "STREAM_SAVE")
    private fun result(r:JSONObject,replay:Boolean)=ApiResponse(200,resultJson(r).put("replayed",replay).toString())
    private fun batchError(result:BatchAddResult)=when(result){
        BatchAddResult.CONFLICT->error(409,"QUEUE_VERSION_CONFLICT")
        BatchAddResult.KEY_REUSED->error(409,"IDEMPOTENCY_KEY_REUSED")
        BatchAddResult.RECEIPT_LIMIT->error(429,"COMMAND_STORAGE_LIMIT")
        else->error(503,"COMMAND_STORAGE_ERROR")
    }
    private fun integer(j:JSONObject,key:String):Long {val n=j.get(key);require(n is Int || n is Long);return (n as Number).toLong()}
    private fun safeCode(e:Exception,fallback:String)=e.message?.takeIf {it.matches(Regex("[A-Z][A-Z_0-9]{1,63}"))} ?: fallback
    private fun error(code:Int,error:String)=ApiResponse(code,JSONObject().put("error",error).toString())
    @Synchronized fun close(){previews.values.forEach {it.job?.cancel()};previews.clear()}
}

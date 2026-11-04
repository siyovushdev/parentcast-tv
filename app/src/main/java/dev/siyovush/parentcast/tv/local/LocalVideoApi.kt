package dev.siyovush.parentcast.tv.local

import dev.siyovush.parentcast.tv.prepared.*
import dev.siyovush.parentcast.tv.playback.PlaybackCoordinator
import dev.siyovush.parentcast.tv.web.additionJson
import dev.siyovush.parentcast.tv.queue.BatchAddResult
import dev.siyovush.parentcast.tv.web.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

internal data class ImportJob(val id: String, val owner: String, val mediaId: String, val name: String, val size: Long,
    val status: String = "WAITING", val bytes: Long = 0, val error: String = "", val created: Long = System.currentTimeMillis())
internal class LocalVideoApi(
    private val library: PreparedLibrary, private val coordinator: PlaybackCoordinator, private val dispatcher: CallDispatcher,
    private val jobsFile: File, private val scope: CoroutineScope, private val validate: (File) -> LocalMediaInfo,
    private val usb: UsbFiles? = null,
) {
    private val jobs = linkedMapOf<String,ImportJob>()
    private val running = ConcurrentHashMap<String,Job>()
    private val streams = ConcurrentHashMap<String,InputStream>()
    private val slots = Semaphore(2)
    private var storageError = false
    init {
        try { if(jobsFile.exists()) {
            check(jobsFile.length()<=2_000_000)
            val a=JSONArray(jobsFile.readText()); require(a.length()<=1000)
            repeat(a.length()) { i -> val j=a.getJSONObject(i)
                val old=ImportJob(j.getString("id"),j.getString("owner"),j.getString("mediaId"),j.getString("name"),j.getLong("size"),j.getString("status"),j.optLong("bytes"),j.optString("error"),j.getLong("created"))
                require(old.id.matches(Regex("[a-zA-Z0-9-]{36}")) && old.mediaId.matches(LOCAL_ID))
                val ready=library.localEntries().any { it.id==old.mediaId }
                jobs[old.id]=if(ready) old.copy(status="READY",bytes=old.size,error="") else if(old.status in setOf("COPYING","VERIFYING","QUEUED")) old.copy(status="ERROR",error="IMPORT_INTERRUPTED") else old
                if(!ready) library.discardIncompleteImport(old.mediaId)
            }
        } } catch (_: Exception) { storageError=true;jobs.clear() }
    }
    @Synchronized private fun save() {
        check(!storageError) { "IMPORT_STORAGE_ERROR" }
        jobsFile.parentFile?.mkdirs()
        val a=JSONArray(); jobs.values.forEach { j -> a.put(json(j).put("owner",j.owner).put("created",j.created)) }
        val tmp=File(jobsFile.path+".tmp")
        FileOutputStream(tmp).use { it.write(a.toString().toByteArray()); it.fd.sync() }
        check(tmp.renameTo(jobsFile)) { "IMPORT_STORAGE_ERROR" }
    }
    private fun json(j: ImportJob) = JSONObject().put("id",j.id).put("mediaId",j.mediaId).put("name",j.name).put("size",j.size).put("status",j.status).put("bytes",j.bytes).put("error",j.error)
    @Synchronized private fun change(id: String, persist: Boolean = true, action: (ImportJob)->ImportJob) {
        jobs[id]=action(jobs.getValue(id)); if(persist) save()
    }
    @Synchronized private fun job(id: String, owner: String): ImportJob = jobs[id]?.takeIf { it.owner==owner } ?: error("IMPORT_NOT_FOUND")
    @Synchronized private fun create(owner: String, name: String, size: Long, status: String = "WAITING", saveNow: Boolean = true): ImportJob {
        require(name.isNotBlank() && name.length<=200 && name.none { it.code<32 } && size in 1..library.limit) { "BAD_MEDIA_INPUT" }
        val old=System.currentTimeMillis()-86_400_000
        jobs.entries.removeAll { it.value.created<old && !running.containsKey(it.key) }
        check(jobs.size<1000) { "IMPORT_LIMIT" }
        val id=UUID.randomUUID().toString(); var mediaId: String
        do { mediaId="local_"+UUID.randomUUID().toString().replace("-","") } while(library.snapshot().entries.containsKey(mediaId) || jobs.values.any { it.mediaId==mediaId })
        val result=ImportJob(id,owner,mediaId,name,size,status); jobs[id]=result; if(saveNow) {try {save()} catch(error:Exception){jobs.remove(id);throw error}}; return result
    }
    fun read(path: String, owner: String): ApiResponse? = when {
        storageError && path=="/api/local" -> ApiResponse(503,"{\"error\":\"IMPORT_STORAGE_ERROR\"}")
        path=="/api/local" -> {
            val files=JSONArray(); library.localEntries().forEach { e -> files.put(JSONObject().put("videoId",e.id).put("title",e.title).put("bytes",e.bytes).put("durationSeconds",e.duration)) }
            val tasks=JSONArray(); synchronized(this) { jobs.values.filter { it.owner==owner }.takeLast(50).forEach { tasks.put(json(it)) } }
            ApiResponse(200,JSONObject().put("files",files).put("jobs",tasks).put("usbConnected",usb?.connected()==true).toString())
        }
        path=="/api/usb" || path.startsWith("/api/usb/") -> try {
            val key=if(path=="/api/usb") "root" else path.removePrefix("/api/usb/")
            require(key=="root" || key.matches(Regex("[A-Za-z0-9-]{36}")))
            val entries=JSONArray(); (usb ?: error("USB_UNAVAILABLE")).list(key).forEach { item -> entries.put(JSONObject().put("key",item.key).put("name",item.name).put("size",item.size).put("folder",item.folder)) }
            ApiResponse(200,JSONObject().put("entries",entries).put("folder",key).toString())
        } catch (_: Exception) { ApiResponse(409,"{\"error\":\"USB_UNAVAILABLE_OR_PERMISSION_REQUIRED\"}") }
        else -> null
    }
    fun handles(path: String) = path in setOf("/api/local/start","/api/local/cancel","/api/local/add","/api/local/delete","/api/usb/import")
    fun mutate(path: String, owner: String, j: JSONObject, key: String, fingerprint: String): Pair<Int,String> = try {
        when(path) {
            "/api/local/start" -> 201 to json(create(owner,j.optString("name"),j.optLong("size",-1))).toString()
            "/api/local/cancel" -> {
                val task=job(j.optString("jobId"),owner)
                if(task.status!="READY") {
                    change(task.id) { it.copy(status="CANCELLED",error="") }; running[task.id]?.cancel(); runCatching { streams[task.id]?.close() }
                }
                200 to json(job(task.id,owner)).toString()
            }
            "/api/local/add" -> {
                val a=j.getJSONArray("videoIds");require(a.length() in 1..500)
                val ids=List(a.length()) { a.getString(it) }; val available=library.localEntries().map { it.id }.toSet()
                require(ids.all { it in available }) { "LOCAL_FILE_NOT_FOUND" }
                val result=dispatcher.call { coordinator.addBatch(ids,j.optLong("queueVersion",-1),key,fingerprint,local=true) }
                when(result) {
                    BatchAddResult.APPLIED,BatchAddResult.REPLAY -> 200 to JSONObject().put("added",true)
                        .put("addition",additionJson(dispatcher.call { coordinator.additionSummary(key) })).toString()
                    BatchAddResult.CONFLICT -> 409 to "{\"error\":\"QUEUE_VERSION_CONFLICT\"}"
                    BatchAddResult.KEY_REUSED -> 409 to "{\"error\":\"IDEMPOTENCY_KEY_REUSED\"}"
                    else -> 503 to "{\"error\":\"COMMAND_STORAGE_ERROR\"}"
                }
            }
            "/api/local/delete" -> {
                val id=j.optString("videoId");require(library.localEntries().any { it.id==id }) { "LOCAL_FILE_NOT_FOUND" }
                val allowed=dispatcher.call {
                    val q=coordinator.currentSnapshot().queueState
                    if(q.version!=j.optLong("queueVersion",-1)) false
                    else {
                        if(q.currentItem?.let { it.local && it.videoId==id }==true) {
                            check(j.optBoolean("stopPlayback")) { "FILE_IN_USE" };coordinator.stop()
                        };true
                    }
                }
                if(!allowed) 409 to "{\"error\":\"QUEUE_VERSION_CONFLICT\"}"
                else { runBlocking(Dispatchers.IO) { library.deleteMedia(id) };202 to "{\"deleting\":true}" }
            }
            "/api/usb/import" -> {
                val a=j.getJSONArray("keys");require(a.length() in 1..20)
                val selections=List(a.length()) { (usb ?: error("USB_UNAVAILABLE")).capture(a.getString(it)) }
                require(selections.all { it.item.name.isNotBlank() && it.item.name.length<=200 && it.item.size in 1..library.limit }) { "BAD_MEDIA_INPUT" }
                val tasks=synchronized(this) {
                    check(jobs.size+selections.size<=1000) { "IMPORT_LIMIT" }
                    val batch=selections.map {create(owner,it.item.name,it.item.size,"QUEUED",false)}
                    try {save()} catch(error:Exception){batch.forEach {jobs.remove(it.id)};throw error};batch
                }
                scope.launch(Dispatchers.IO) {
                    tasks.zip(selections).forEach { (task,selection) ->
                        if(job(task.id,owner).status!="CANCELLED") {
                            while(!slots.tryAcquire()) { delay(250); if(job(task.id,owner).status=="CANCELLED") return@forEach }
                            try { selection.open().use { input -> perform(task,input) } }
                            catch (_: Exception) { if(job(task.id,owner).status !in setOf("CANCELLED","ERROR")) change(task.id) { it.copy(status="ERROR",error="USB_REMOVED_OR_UNAVAILABLE") } }
                            finally { slots.release() }
                        }
                    }
                }
                202 to JSONObject().put("jobs",JSONArray(tasks.map { json(it) })).toString()
            }
            else -> 404 to "{\"error\":\"NOT_FOUND\"}"
        }
    } catch (error: Exception) {
        val code=error.message?.takeIf { it in setOf("BAD_MEDIA_INPUT","LOCAL_FILE_NOT_FOUND","FILE_IN_USE","BUSY","IMPORT_LIMIT","USB_UNAVAILABLE","USB_LIST_EXPIRED","USB_UNKNOWN_SIZE","IMPORT_STORAGE_ERROR") } ?: "BAD_REQUEST"
        (if(code=="IMPORT_STORAGE_ERROR" || error is IOException) 503 else if(code in setOf("BUSY","FILE_IN_USE","IMPORT_LIMIT")) 409 else 400) to JSONObject().put("error",code).toString()
    }
    fun upload(id: String, owner: String, length: Long, input: InputStream): ApiResponse {
        val task=try { job(id,owner) } catch (_: Exception) { return ApiResponse(404,"{\"error\":\"IMPORT_NOT_FOUND\"}") }
        if(task.size!=length) return ApiResponse(400,"{\"error\":\"UPLOAD_SIZE_MISMATCH\"}")
        if(task.status=="READY") return ApiResponse(200,json(task).toString())
        if(task.status=="CANCELLED") return ApiResponse(409,"{\"error\":\"IMPORT_CANCELLED\"}")
        if(!slots.tryAcquire()) return ApiResponse(409,"{\"error\":\"IMPORT_BUSY\"}")
        return try {
            runBlocking(Dispatchers.IO) { perform(task,ExactUploadBody(input,length)) }
            ApiResponse(201,json(job(id,owner)).toString())
        } catch (error: Exception) {
            if(error.message=="IMPORT_BUSY") ApiResponse(409,"{\"error\":\"IMPORT_BUSY\"}") else ApiResponse(422,json(job(id,owner)).toString())
        }
        finally { slots.release() }
    }
    private suspend fun perform(task: ImportJob, input: InputStream) {
        check(running.putIfAbsent(task.id,currentCoroutineContext()[Job]!!)==null) { "IMPORT_BUSY" }
        streams[task.id]=input
        try {
            check(job(task.id,task.owner).status!="CANCELLED") { "IMPORT_CANCELLED" }
            change(task.id) { it.copy(status="COPYING",bytes=0,error="") }
            val progress=object : FilterInputStream(input) {
                var copied=0L
                override fun read(b: ByteArray,off: Int,len: Int): Int {
                    val count=super.read(b,off,len); if(count>0) { copied+=count;change(task.id,false) { it.copy(bytes=copied) } };return count
                }
            }
            library.importLocal(task.mediaId,task.name,task.size,progress) { file ->
                change(task.id) { it.copy(status="VERIFYING") };validate(file)
            }
            change(task.id) { it.copy(status="READY",bytes=it.size,error="") }
        } catch (error: Throwable) {
            val code=error.message?.takeIf { it in setOf("NO_SPACE","UNSUPPORTED_MEDIA","UNSUPPORTED_CODEC","UPLOAD_INCOMPLETE","UPLOAD_SIZE_MISMATCH","BUSY","STORAGE_ERROR") } ?: "IMPORT_FAILED"
            change(task.id) { if(it.status=="CANCELLED") it else it.copy(status="ERROR",error=code) }
            throw error
        } finally { running.remove(task.id);streams.remove(task.id) }
    }
    fun close() { running.values.forEach { it.cancel() };streams.values.forEach { runCatching { it.close() } } }
}

/** HTTP framing is bounded; the importer can check EOF without reading the next request. */
private class ExactUploadBody(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
    override fun read(): Int {
        if(remaining==0L)return -1
        val value=super.read();if(value>=0)remaining--;return value
    }
    override fun read(b: ByteArray,off: Int,len: Int): Int {
        if(len==0)return 0
        if(remaining==0L)return -1
        val count=super.read(b,off,minOf(len.toLong(),remaining).toInt());if(count>0)remaining-=count;return count
    }
}

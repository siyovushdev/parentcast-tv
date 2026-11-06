package dev.siyovush.parentcast.tv.streaming

import androidx.media3.datasource.DataSource
import dev.siyovush.parentcast.tv.media.*
import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Persist parent choices, never URLs or video bytes. Playback bypasses the prepared library. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OnlinePlayback(private val journal:File,private val session:YouTubeSession,
    private val preview:suspend (String,Boolean)->VideoPreview,
) {
    private data class Choice(val id:String,val quality:String,val height:Int)
    private val choices=ConcurrentHashMap<String,Choice>()
    private val resolved=ConcurrentHashMap<Choice,ResolvedVideo>()
    private val resolving=ConcurrentHashMap<String,Any>()
    private val calls=ConcurrentHashMap<Call,String>()
    private val client=OnlineHttpSource.client()
    @Volatile private var healthy=true
    @Volatile private var current:String?=null
    init { if(journal.isFile)try {
        val root=JSONObject(journal.readText());require(root.getInt("version")==1)
        val a=root.getJSONArray("videos");require(a.length()<=10_000)
        repeat(a.length()){n->val j=a.getJSONObject(n);val id=j.getString("id");val q=j.getString("quality");val h=j.getInt("height")
            require(OnlineMediaIds.isOnline(id) && q.matches(Regex("[a-f0-9]{64}")) && h in 1..1080)
            choices[id]=Choice(id,q,h) }
    }catch(_:Exception){healthy=false;choices.clear()} }
    fun enabled(id:String)=healthy && choices.containsKey(id)
    fun height(id:String)=choices[id]?.height
    suspend fun select(id:String,quality:String) = withContext(Dispatchers.IO) {
        require(OnlineMediaIds.isOnline(id) && quality.matches(Regex("[a-f0-9]{64}")))
        check(healthy){"ONLINE_SELECTION_ERROR"}
        val selected=preview(id,false).qualities.find {it.id==quality} ?: error("QUALITY_UNAVAILABLE")
        val choice=Choice(id,quality,selected.height)
        synchronized(this@OnlinePlayback) {
            check(choices.size<10_000 || choices.containsKey(id)){"ONLINE_SELECTION_LIMIT"}
            val next=choices.toMutableMap().apply {put(id,choice)};persist(next);choices[id]=choice;resolved[choice]=selected.resolved
        }
    }
    @Synchronized fun forget(id:String) {
        if(!choices.containsKey(id))return
        val next=choices.toMutableMap().apply {remove(id)};persist(next);choices.remove(id)
    }
    private suspend fun video(choice:Choice,refresh:Boolean=false):ResolvedVideo = withContext(Dispatchers.IO) {
        if(!refresh)resolved[choice]?.let {return@withContext it}
        synchronized(resolving.getOrPut(choice.id){Any()}) {
            if(!refresh)resolved[choice]?.let {return@synchronized it}
            val next=runBlocking {preview(choice.id,refresh)}.qualities.find {it.id==choice.quality}?.resolved ?: error("QUALITY_UNAVAILABLE")
            val old=resolved[choice]
            if(old!=null && (listOfNotNull(old.video,old.audio).map {it.resumeIdentity to it.sizeBytes} != listOfNotNull(next.video,next.audio).map {it.resumeIdentity to it.sizeBytes}))error("REPRESENTATION_CHANGED")
            resolved[choice]=next;next
        }
    }
    fun source(fallback:YouTubeSource)=object:YouTubeSource {
        override suspend fun resolve(videoId:String):ResolvedVideo {
            val choice=choices[videoId]?.takeIf {healthy} ?: return fallback.resolve(videoId)
            val v=video(choice)
            fun marker(s:StreamSpec,audio:Boolean)=s.copy(url="parentcast-online://${if(audio)"audio" else "video"}",sharedCacheKey=null,
                onlineKey="$videoId|${choice.quality}|${if(audio)1 else 0}")
            return v.copy(video=marker(v.video,false),audio=v.audio?.let {marker(it,true)},selectionDescription="${choice.height}p · онлайн без сохранения · H.264/AAC")
        }
    }
    fun dataSourceFactory(spec:StreamSpec):DataSource.Factory? {
        val key=spec.onlineKey ?: return null
        val parts=key.split('|');require(parts.size==3)
        val choice=choices[parts[0]]?.takeIf {it.quality==parts[1]} ?: error("ONLINE_SELECTION_CHANGED")
        val audio=parts[2]=="1"
        return DataSource.Factory {OnlineHttpSource(session,{refresh->
            val v=runBlocking {video(choice,refresh)};if(audio)checkNotNull(v.audio) else v.video
        },client,register={call->
            calls[call]=choice.id
            if(current!=choice.id){calls.remove(call);call.cancel();throw java.io.IOException("TRANSFER_CANCELLED")}
        },unregister={calls.remove(it)})}
    }
    fun syncPlayback(id:String?) {current=id;calls.entries.filter {it.value!=id}.forEach {it.key.cancel()}}
    fun close(){current=null;calls.keys.forEach(Call::cancel);calls.clear();resolved.clear()}
    private fun persist(next:Map<String,Choice>) {
        check(healthy){"ONLINE_SELECTION_ERROR"}
        journal.parentFile?.mkdirs();val tmp=File(journal.path+".tmp")
        try {FileOutputStream(tmp).use {out->out.write(JSONObject().put("version",1).put("videos",JSONArray().apply {
            next.values.forEach {put(JSONObject().put("id",it.id).put("quality",it.quality).put("height",it.height))}
        }).toString().toByteArray());out.fd.sync()}
            Files.move(tmp.toPath(),journal.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }finally{tmp.delete()}
    }
}

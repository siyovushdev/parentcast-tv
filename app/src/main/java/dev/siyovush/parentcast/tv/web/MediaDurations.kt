package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.media.OnlineMediaIds
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Derived metadata only; no queue writes, media downloads or implicit playback. */
internal class MediaDurations(
    private val file: File,
    scope: CoroutineScope,
    private val fetch: suspend (String) -> Long?,
    private val now: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val values = linkedMapOf<String,Long>()
    private val pending = mutableSetOf<String>()
    private val failed = linkedMapOf<String,Long>()
    private val worker = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + dispatcher)
    private val slot = kotlinx.coroutines.sync.Semaphore(1)
    init { runCatching {
        if(file.isFile && file.length()<=1_048_576) {
            val json=JSONObject(file.readText())
            json.keys().asSequence().take(10_000).forEach { id -> val duration=json.optLong(id);if(OnlineMediaIds.isOnline(id) && valid(duration))values[id]=duration }
        }
    } }
    @Synchronized fun known(id: String): Long? = values[id]
    @Synchronized fun rememberAll(durations: Map<String,Long>) {
        var changed=false
        durations.forEach { (id,duration) -> if(OnlineMediaIds.isOnline(id) && valid(duration) && values[id]!=duration) { values[id]=duration;failed.remove(id);changed=true } }
        while(values.size>10_000)values.remove(values.keys.first())
        if(changed) runCatching {
            file.parentFile?.mkdirs();val temp=File(file.path+".tmp");temp.writeText(JSONObject(values as Map<*,*>).toString());check(temp.renameTo(file))
        }
    }
    @Synchronized fun getOrRequest(id: String): Long? {
        values[id]?.let { return it }
        if(!OnlineMediaIds.isOnline(id) || id in pending || pending.size>=8 || failed[id]?.let { now()-it<300_000 }==true)return null
        pending+=id
        worker.launch {
            try {
                slot.acquire()
                try {
                    val duration=withTimeoutOrNull(25_000) { fetch(id) }
                    if(duration!=null && valid(duration))rememberAll(mapOf(id to duration)) else synchronized(this@MediaDurations){failed[id]=now()}
                } finally { slot.release() }
            } catch(e: CancellationException){throw e}
              catch(_:Exception){synchronized(this@MediaDurations){failed[id]=now()}}
            finally {synchronized(this@MediaDurations){pending-=id;while(failed.size>2000)failed.remove(failed.keys.first())}}
        }
        return null
    }
    fun close() = worker.cancel()
    companion object { fun valid(value: Long) = value in 1 until 315_360_000L }
}

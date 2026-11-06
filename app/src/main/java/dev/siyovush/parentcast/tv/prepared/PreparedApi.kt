package dev.siyovush.parentcast.tv.prepared

import dev.siyovush.parentcast.tv.playback.PlaybackCoordinator
import dev.siyovush.parentcast.tv.web.CallDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal class PreparedApi(private val library: PreparedLibrary, private val coordinator: PlaybackCoordinator, private val dispatcher: CallDispatcher, private val metadataDuration: (String) -> Long? = { null }) {
    val uploadLimit:Long get() = EXTERNAL_LIBRARY_LIMIT
    fun readiness(id: String): String = library.mediaReadiness(id)
    fun canStream(id:String)=library.streaming?.canPlayDuringDownload(id)==true
    fun canPlay(id: String): Boolean = readiness(id) == "READY" || library.streaming?.canPlayDuringDownload(id)==true ||
        (readiness(id)!="STORAGE_BUSY" && readiness(id)!="STORAGE_UNAVAILABLE" && !library.snapshot().enabled && !id.startsWith("local_") && !dev.siyovush.parentcast.tv.media.OnlineMediaIds.isVk(id))
    fun state(): JSONObject {
        val s = library.snapshot(); val entries = JSONArray()
        val queueItems=dispatcher.call { coordinator.currentSnapshot().queueState.items }
        val titles=queueItems.associate { it.videoId to it.title }
        val queueIds=queueItems.map { it.videoId }.toSet()
        s.plan.forEachIndexed { index, id ->
            val e = s.entries[id] ?: PreparedEntry(id)
            entries.put(JSONObject().put("index", index + 1).put("videoId", id).put("title", e.title.ifBlank { titles[id].orEmpty() }).put("status", readiness(id)).put("storageId",library.mediaStorageId(id)).put("available",library.storageAvailable(id))
                .put("durationSeconds",e.duration.takeIf { it>0 } ?: metadataDuration(e.id) ?: JSONObject.NULL).put("bytes", e.bytes).put("totalBytes", e.total.takeIf { e.totalKnown || e.status == PreparationStatus.READY } ?: JSONObject.NULL)
                .put("percent", if (id in s.deleting) JSONObject.NULL else preparationPercent(e) ?: JSONObject.NULL).put("phase", e.phase)
                .put("error", e.error).put("attempts", e.attempts).put("retryAtMs", e.retryAtMs))
        }
        val files = JSONArray()
        s.entries.values.filter { !it.local && (it.status == PreparationStatus.READY || it.id in s.deleting) }.forEach { e ->
            files.put(JSONObject().put("videoId", e.id).put("title", e.title).put("bytes", e.bytes)
                .put("durationSeconds",e.duration.takeIf { it>0 } ?: metadataDuration(e.id) ?: JSONObject.NULL).put("deleting", e.id in s.deleting).put("inQueue", e.id in queueIds).put("storageId",library.mediaStorageId(e.id)).put("available",library.storageAvailable(e.id)))
        }
        val storage = library.storageStats()
        return JSONObject().put("files", files).put("clearing", s.deleting.isNotEmpty()).put("operationError", s.operationError).put("usedBytes", storage.first).put("freeBytes", storage.second)
            .put("additionalBytes", minOf((library.limit - storage.first).coerceAtLeast(0), (storage.second - FREE_RESERVE).coerceAtLeast(0)))
            .put("targetGroupId",s.targetGroupId ?: JSONObject.NULL).put("enabled", s.enabled).put("paused", s.paused).put("storageError", s.storageError)
            .put("ready", s.plan.count { readiness(it)=="READY" }).put("count", s.plan.size)
            .put("limitBytes", library.limit).put("reserveBytes", FREE_RESERVE).put("entries", entries)
    }
    fun mutate(json: JSONObject): Pair<Int, String> {
        val action = json.optString("action")
        if (action !in setOf("ENABLE", "DISABLE", "PAUSE", "RETRY", "CLEANUP", "DELETE", "DELETE_ALL", "RETRY_DELETE")) return 400 to "{\"error\":\"BAD_REQUEST\"}"
        val deleteId = json.optString("videoId")
        if (action == "DELETE" && !VIDEO_ID.matches(deleteId)) return 400 to "{\"error\":\"BAD_REQUEST\"}"
        val snapshot = dispatcher.call { coordinator.currentSnapshot() }
        if (action in setOf("ENABLE", "DELETE", "DELETE_ALL") && json.optLong("queueVersion", -1) != snapshot.queueState.version) {
            return 409 to "{\"error\":\"QUEUE_VERSION_CONFLICT\"}"
        }
        val groupId = json.optString("groupId").takeIf { it.isNotBlank() }
        val group = groupId?.let { id -> snapshot.queueState.groups.find { it.id == id } ?: return 400 to "{\"error\":\"BAD_REQUEST\"}" }
        val explicit=if(json.has("videoIds"))try {
            require(action=="ENABLE" && groupId==null);val values=json.getJSONArray("videoIds");require(values.length() in 1..500)
            List(values.length()){ values.getString(it) }.also { ids ->require(ids.distinct().size==ids.size && ids.all { id -> snapshot.queueState.items.any { it.videoId==id && !it.local } }) }
        }catch(_:Exception){return 400 to "{\"error\":\"BAD_REQUEST\"}"} else null
        val ids = explicit ?: group?.videoIds ?: snapshot.queueState.remainingItems().filterNot { it.local }.map { it.videoId }
        return try {
            if (action in setOf("DELETE", "DELETE_ALL")) {
                val result = dispatcher.call {
                    val current = coordinator.currentSnapshot().queueState
                    when {
                        current.version != json.optLong("queueVersion", -1) -> 409
                        (action == "DELETE_ALL" || current.currentItem?.videoId == deleteId) && !json.optBoolean("stopPlayback") -> 423
                        else -> {
                            if (action == "DELETE_ALL" || current.currentItem?.videoId == deleteId) coordinator.stop()
                            200
                        }
                    }
                }
                if (result != 200) return (if (result == 423) 409 else result) to
                    if (result == 423) "{\"error\":\"FILE_IN_USE\"}" else "{\"error\":\"QUEUE_VERSION_CONFLICT\"}"
            }
            runBlocking(Dispatchers.IO) {
                when (action) {
                    "ENABLE" -> {
                        val storageId=json.optString("storageId").takeIf { it.isNotEmpty() }
                        val storageVersion=if(json.has("storageVersion"))json.get("storageVersion").also { require(it is Int || it is Long) }.let { (it as Number).toLong() } else null
                        require((storageId==null)==(storageVersion==null))
                        library.configure(true, ids, groupId,storageId,storageVersion,explicitSelection=json.has("videoIds"))
                    }
                    "DISABLE" -> library.configure(false, ids)
                    "PAUSE" -> library.pause()
                    "RETRY" -> library.retry()
                    "CLEANUP" -> library.cleanup()
                    "DELETE" -> library.deleteMedia(deleteId)
                    "DELETE_ALL" -> library.deleteMedia()
                    "RETRY_DELETE" -> library.retryDeletion()
                }
            }
            dispatcher.call { val q = coordinator.currentSnapshot().queueState
                library.syncQueue(q) }
            (if (action in setOf("DELETE", "DELETE_ALL", "RETRY_DELETE")) 202 else 200) to state().toString()
        } catch (error: Exception) {
            if(error.message=="STORAGE_CHANGED")return 409 to "{\"error\":\"STORAGE_CHANGED\"}"
            if(error is StorageUnavailable)return 409 to JSONObject().put("error",error.code).toString()
            (if (error.message == "BUSY") 409 else 503) to "{\"error\":\"PREPARATION_BUSY_OR_STORAGE_ERROR\"}"
        }
    }
}

internal fun preparationPercent(e: PreparedEntry): Int? = when {
    e.status == PreparationStatus.READY -> 100
    e.status == PreparationStatus.WAITING -> if (e.bytes == 0L) 0 else if (e.totalKnown && e.total > 0) (e.bytes.toDouble() * 100 / e.total).toInt().coerceIn(0,99) else null
    e.status == PreparationStatus.DOWNLOADING && e.totalKnown && e.total > 0 ->
        (e.bytes.toDouble() * 100 / e.total).toInt().coerceIn(0, 99)
    else -> null
}

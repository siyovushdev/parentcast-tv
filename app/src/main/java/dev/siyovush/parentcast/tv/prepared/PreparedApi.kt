package dev.siyovush.parentcast.tv.prepared

import dev.siyovush.parentcast.tv.playback.PlaybackCoordinator
import dev.siyovush.parentcast.tv.web.CallDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal class PreparedApi(private val library: PreparedLibrary, private val coordinator: PlaybackCoordinator, private val dispatcher: CallDispatcher) {
    fun state(): JSONObject {
        val s = library.snapshot(); val entries = JSONArray()
        s.plan.forEachIndexed { index, id ->
            val e = s.entries[id] ?: PreparedEntry(id)
            entries.put(JSONObject().put("index", index + 1).put("videoId", id).put("title", e.title).put("status", if (id in s.deleting) "DELETING" else e.status.name)
                .put("bytes", e.bytes).put("totalBytes", e.total.takeIf { e.totalKnown || e.status == PreparationStatus.READY } ?: JSONObject.NULL)
                .put("percent", if (id in s.deleting) JSONObject.NULL else preparationPercent(e) ?: JSONObject.NULL).put("phase", e.phase)
                .put("error", e.error))
        }
        val files = JSONArray()
        s.entries.values.filter { it.status == PreparationStatus.READY || it.id in s.deleting }.forEach { e ->
            files.put(JSONObject().put("videoId", e.id).put("title", e.title).put("bytes", e.bytes)
                .put("deleting", e.id in s.deleting).put("inQueue", e.id in s.plan))
        }
        val storage = library.storageStats()
        return JSONObject().put("files", files).put("clearing", s.deleting.isNotEmpty()).put("operationError", s.operationError).put("usedBytes", storage.first).put("freeBytes", storage.second)
            .put("additionalBytes", minOf((library.limit - storage.first).coerceAtLeast(0), (storage.second - FREE_RESERVE).coerceAtLeast(0)))
            .put("enabled", s.enabled).put("paused", s.paused).put("storageError", s.storageError)
            .put("ready", s.plan.count { s.entries[it]?.status == PreparationStatus.READY && it !in s.deleting }).put("count", s.plan.size)
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
        val ids = snapshot.queueState.let { it.items.drop(it.currentIndex.coerceAtLeast(0)).map { item -> item.videoId } }
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
                    "ENABLE" -> library.configure(true, ids)
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
                library.schedule(q.items.drop(q.currentIndex.coerceAtLeast(0)).map { it.videoId }) }
            (if (action in setOf("DELETE", "DELETE_ALL", "RETRY_DELETE")) 202 else 200) to state().toString()
        } catch (error: Exception) {
            (if (error.message == "BUSY") 409 else 503) to "{\"error\":\"PREPARATION_BUSY_OR_STORAGE_ERROR\"}"
        }
    }
}

internal fun preparationPercent(e: PreparedEntry): Int? = when {
    e.status == PreparationStatus.READY -> 100
    e.status == PreparationStatus.WAITING -> 0
    e.status == PreparationStatus.DOWNLOADING && e.totalKnown && e.total > 0 ->
        (e.bytes.toDouble() * 100 / e.total).toInt().coerceIn(0, 99)
    else -> null
}

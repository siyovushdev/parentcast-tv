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
            entries.put(JSONObject().put("index", index + 1).put("videoId", id).put("title", e.title).put("status", e.status.name)
                .put("bytes", e.bytes).put("error", e.error))
        }
        return JSONObject().put("enabled", s.enabled).put("paused", s.paused).put("storageError", s.storageError)
            .put("ready", s.plan.count { s.entries[it]?.status == PreparationStatus.READY }).put("count", s.plan.size)
            .put("limitBytes", LIBRARY_LIMIT).put("reserveBytes", FREE_RESERVE).put("entries", entries)
    }
    fun mutate(json: JSONObject): Pair<Int, String> {
        val action = json.optString("action")
        if (action !in setOf("ENABLE", "DISABLE", "PAUSE", "RETRY", "CLEANUP")) return 400 to "{\"error\":\"BAD_REQUEST\"}"
        val snapshot = dispatcher.call { coordinator.currentSnapshot() }
        if (action == "ENABLE" && json.optLong("queueVersion", -1) != snapshot.queueState.version) {
            return 409 to "{\"error\":\"QUEUE_VERSION_CONFLICT\"}"
        }
        val ids = snapshot.queueState.let { it.items.drop(it.currentIndex.coerceAtLeast(0)).map { item -> item.videoId } }
        return try {
            runBlocking(Dispatchers.IO) {
                when (action) {
                    "ENABLE" -> library.configure(true, ids)
                    "DISABLE" -> library.configure(false, ids)
                    "PAUSE" -> library.pause()
                    "RETRY" -> library.retry()
                    "CLEANUP" -> library.cleanup()
                }
            }
            dispatcher.call { val q = coordinator.currentSnapshot().queueState
                library.schedule(q.items.drop(q.currentIndex.coerceAtLeast(0)).map { it.videoId }) }
            200 to state().toString()
        } catch (error: Exception) {
            (if (error.message == "BUSY") 409 else 503) to "{\"error\":\"PREPARATION_BUSY_OR_STORAGE_ERROR\"}"
        }
    }
}

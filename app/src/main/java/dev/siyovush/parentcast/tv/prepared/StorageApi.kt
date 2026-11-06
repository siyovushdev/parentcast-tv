package dev.siyovush.parentcast.tv.prepared

import dev.siyovush.parentcast.tv.playback.PlaybackCoordinator
import dev.siyovush.parentcast.tv.queue.PlaybackIntent
import dev.siyovush.parentcast.tv.web.CallDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Routed only through the existing authenticated, CSRF-protected idempotent API envelope. */
internal class StorageApi(private val library:PreparedLibrary,private val coordinator:PlaybackCoordinator,private val dispatcher:CallDispatcher) {
    fun state():JSONObject {
        val storage=library.storage
        val migration=storage.migration()
        return JSONObject().put("storageVersion",storage.version()).put("selectedId",storage.selectedId())
            .put("storageError",!storage.healthy()).put("retainedOriginalBytes",storage.retained().sumOf { o -> o.tracks.sumOf { it.bytes } })
            .put("incompleteCopyBytes",storage.stagedCopies().sumOf { copy -> runCatching {
                val name=PreparedStorage.physicalName(copy.id,copy.track.name,copy.storageId!=INTERNAL_STORAGE)
                val root=storage.root(copy.storageId);PreparedStorage.safeFile(root,name).length()+PreparedStorage.safeFile(root,name+".copying").length()
            }.getOrDefault(0L) })
            .put("volumes",JSONArray().apply { storage.volumes().forEach { v -> put(JSONObject().put("id",v.id).put("name",v.name)
                .put("removable",v.removable).put("mounted",v.mounted).put("writable",v.writable)
                .put("freeBytes",if(v.mounted)v.freeBytes else JSONObject.NULL).put("totalBytes",if(v.mounted)v.totalBytes else JSONObject.NULL)
                .put("limitBytes",if(v.mounted)storage.limit(v.id) else JSONObject.NULL).put("usedBytes",if(v.mounted)storage.usedBytes(v.id) else JSONObject.NULL)) } })
            .put("migration",JSONObject().put("status",migration.status).put("targetId",migration.targetId)
                .put("totalFiles",migration.totalFiles).put("completedFiles",migration.completedFiles).put("copiedBytes",migration.copiedBytes).put("totalBytes",migration.totalBytes).put("error",migration.error))
    }
    fun mutate(json:JSONObject,commandKey:String,fingerprint:String):Pair<Int,String> {
        val command=StorageCommand(commandKey,fingerprint)
        return try {
            if(library.storage.replay(command)) return 200 to state().put("replayed",true).toString()
            val version=json.get("storageVersion");require(version is Int || version is Long);require((version as Number).toLong()>=0)
            val action=json.getString("action")
            runBlocking(Dispatchers.IO) {
                when(action) {
                    "SELECT" -> library.selectStorage(json.getString("storageId"),version.toLong(),command)
                    "MIGRATE" -> library.migrateStorage(json.getString("storageId"),version.toLong(),command)
                    "CANCEL_MIGRATION" -> library.cancelStorageMigration(version.toLong(),command)
                    "CONFIRM_CLEANUP" -> {
                        require(json.optBoolean("confirm") && json.optBoolean("stopPlayback"))
                        val q=json.get("queueVersion");require(q is Int || q is Long)
                        val expected=(q as Number).toLong()
                        val initial=dispatcher.call { coordinator.currentSnapshot().queueState }
                        check(initial.version==expected) { "QUEUE_VERSION_CONFLICT" }
                        // Require an explicit STOP in the player first; a cleanup request never silently stops viewing.
                        check(initial.intent==PlaybackIntent.STOP) { "STOP_PLAYBACK_FIRST" }
                        library.cleanStorageOriginals(version.toLong(),command) {
                            dispatcher.call { coordinator.currentSnapshot().queueState.let { it.version==expected && it.intent==PlaybackIntent.STOP } }
                        }
                    }
                    else -> return@runBlocking false
                };true
            }.let { valid -> if(!valid) return 400 to error("BAD_REQUEST") }
            (if(action in setOf("MIGRATE","CONFIRM_CLEANUP","CANCEL_MIGRATION"))202 else 200) to state().toString()
        }catch(error:Exception) {
            val code=if(error is StorageUnavailable)error.code else error.message.orEmpty()
            when(code) {
                "STORAGE_CHANGED","QUEUE_VERSION_CONFLICT","STOP_PLAYBACK_FIRST","BUSY","IDEMPOTENCY_KEY_REUSED" -> 409 to error(code)
                "STORAGE_COMMAND_LIMIT" -> 429 to error(code)
                "STORAGE_UNAVAILABLE","STORAGE_READ_ONLY","STORAGE_NOT_FOUND" -> 409 to error(code)
                "STORAGE_CATALOGUE_ERROR" -> 503 to error(code)
                else -> if(error is IllegalArgumentException || error is org.json.JSONException)400 to error("BAD_REQUEST") else 503 to error("STORAGE_ERROR")
            }
        }
    }
    private fun error(code:String)=JSONObject().put("error",code).toString()
}

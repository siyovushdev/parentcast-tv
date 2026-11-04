package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.collections.CollectionLimitException
import dev.siyovush.parentcast.tv.collections.CollectionStore
import dev.siyovush.parentcast.tv.collections.SavedCollection
import dev.siyovush.parentcast.tv.playback.PlaybackCoordinator
import dev.siyovush.parentcast.tv.playlist.*
import dev.siyovush.parentcast.tv.queue.BatchAddResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** All routes are behind ApiController's existing token and request-security checks. */
internal class PlaylistApi(
    private val previews: PlaylistPreviewManager,
    private val collections: CollectionStore,
    private val coordinator: PlaybackCoordinator,
    private val dispatcher: CallDispatcher,
    private val stateBody: () -> String,
) {
    fun handles(path: String): Boolean = path in setOf(
        "/api/playlists/preview", "/api/playlists/cancel", "/api/playlists/apply",
        "/api/collections/save", "/api/collections/apply", "/api/collections/delete",
    )

    fun read(path: String, owner: String): ApiResponse? = safely {
        when {
            path == "/api/collections" -> response(200, JSONObject().put("collections", JSONArray().apply {
                collections.list().forEach { put(collectionJson(it)) }
            }))
            path.startsWith("/api/playlists/preview/") -> {
                val preview = previews.get(owner, path.removePrefix("/api/playlists/preview/"))
                    ?: return@safely failure(404, "PREVIEW_NOT_FOUND")
                response(200, previewJson(preview))
            }
            else -> null
        }
    }

    fun mutate(path: String, owner: String, json: JSONObject, commandKey: String, fingerprint: String): ApiResponse = safely {
        // Receipt is committed with the queue, so retries work even after preview expiry/process death.
        if (path.endsWith("/apply")) {
            dispatcher.call { coordinator.batchReplay(commandKey, fingerprint) }?.let { return@safely batchResponse(it, commandKey) }
        }
        when (path) {
            "/api/playlists/preview" -> {
                val url = json.get("url") as String
                response(202, JSONObject().put("previewId", previews.start(owner, url).id))
            }
            "/api/playlists/cancel" -> {
                val preview = previews.cancel(owner, json.getString("previewId"))
                    ?: return@safely failure(404, "PREVIEW_NOT_FOUND")
                response(200, previewJson(preview))
            }
            "/api/playlists/apply" -> add(selected(owner, json), json, commandKey, fingerprint)
            "/api/collections/save" -> {
                val id = UUID.nameUUIDFromBytes(commandKey.toByteArray()).toString()
                val existing = collections.get(id)
                if (existing != null && existing.creationFingerprint != fingerprint) {
                    return@safely failure(409, "IDEMPOTENCY_KEY_REUSED")
                }
                // Existing snapshot can be acknowledged after process death without refetching YouTube.
                val collection = existing ?: SavedCollection(id, (json.get("name") as String).trim(),
                    selected(owner, json), System.currentTimeMillis(), fingerprint).also(collections::save)
                response(200, JSONObject().put("collection", collectionJson(collection)))
            }
            "/api/collections/apply" -> {
                val collection = collections.get(json.getString("collectionId"))
                    ?: return@safely failure(404, "COLLECTION_NOT_FOUND")
                add(collection.videoIds, json, commandKey, fingerprint)
            }
            "/api/collections/delete" -> {
                collections.delete(json.getString("collectionId"))
                response(200, JSONObject().put("deleted", true))
            }
            else -> failure(404, "NOT_FOUND")
        }
    } ?: failure(404, "NOT_FOUND")

    private fun selected(owner: String, json: JSONObject): List<String> {
        val preview = previews.get(owner, json.getString("previewId")) ?: throw PreviewException(404, "PREVIEW_NOT_FOUND")
        val result = preview.result ?: throw PreviewException(409, "PREVIEW_NOT_READY")
        if (preview.state != PreviewState.READY || result.status !in setOf(PlaylistStatus.COMPLETE, PlaylistStatus.PARTIAL)) {
            throw PreviewException(409, "PREVIEW_NOT_APPLICABLE")
        }
        val indices = json.getJSONArray("indices")
        require(indices.length() in 1..500)
        val values = List(indices.length()) { i ->
            val value = indices.get(i)
            require(value is Int || value is Long)
            (value as Number).toLong().also { require(it in 1..Int.MAX_VALUE) }.toInt()
        }
        require(values.distinct().size == values.size)
        val entries = result.entries.associateBy { it.index }
        return values.sorted().map { index ->
            val entry = entries[index] ?: throw IllegalArgumentException()
            require(entry.selectable && entry.videoId != null)
            entry.videoId!!
        }
    }

    private fun add(ids: List<String>, json: JSONObject, commandKey: String, fingerprint: String): ApiResponse {
        val version = json.get("queueVersion")
        require(version is Int || version is Long)
        require((version as Number).toLong() >= 0)
        return batchResponse(dispatcher.call { coordinator.addBatch(ids, version.toLong(), commandKey, fingerprint) }, commandKey)
    }

    private fun batchResponse(result: BatchAddResult, key: String): ApiResponse =
        queueAdditionResponse(result, dispatcher.call { coordinator.additionSummary(key) }, stateBody())

    private fun previewJson(preview: PlaylistPreview): JSONObject {
        val result = preview.result
        return JSONObject().put("previewId", preview.id)
            .put("status", when (preview.state) {
                PreviewState.LOADING -> "LOADING"
                PreviewState.CANCELLED -> "CANCELLED"
                PreviewState.READY -> result?.status?.name ?: "ERROR"
            })
            .put("title", result?.title ?: "")
            .put("reason", result?.reason ?: JSONObject.NULL)
            .put("reportedCount", result?.reportedCount ?: JSONObject.NULL)
            .put("entries", JSONArray().apply {
                result?.entries?.forEach { entry ->
                    put(JSONObject().put("index", entry.index).put("videoId", entry.videoId ?: JSONObject.NULL)
                        .put("title", entry.title).put("availability", entry.availability.name)
                        .put("selectable", entry.selectable).put("durationSeconds", entry.durationSeconds ?: JSONObject.NULL)
                        .put("channel", entry.channel ?: JSONObject.NULL))
                }
            })
    }

    private fun collectionJson(collection: SavedCollection): JSONObject = JSONObject()
        .put("id", collection.id).put("name", collection.name).put("videoIds", JSONArray(collection.videoIds))
        .put("createdAtMs", collection.createdAtMs)

    private fun response(code: Int, json: JSONObject) = ApiResponse(code, json.toString())
    private fun failure(code: Int, error: String) = response(code, JSONObject().put("error", error))
    private class PreviewException(val code: Int, val error: String) : RuntimeException()
    private fun safely(action: () -> ApiResponse?): ApiResponse? = try {
        action()
    } catch (error: PreviewException) {
        failure(error.code, error.error)
    } catch (_: CollectionLimitException) {
        failure(409, "COLLECTION_STORAGE_LIMIT")
    } catch (_: IOException) {
        failure(503, "COLLECTION_STORAGE_ERROR")
    } catch (_: IllegalStateException) {
        failure(429, "PREVIEW_BUSY")
    } catch (_: Exception) {
        failure(400, "BAD_REQUEST")
    }
}

package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.playback.CoordinatorSnapshot
import dev.siyovush.parentcast.tv.playback.PlaybackCoordinator
import dev.siyovush.parentcast.tv.queue.IdempotencyStore
import dev.siyovush.parentcast.tv.queue.StoredResponse
import org.json.JSONArray
import org.json.JSONObject

internal data class ApiRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
    val clientKey: String,
)

internal data class ApiResponse(
    val code: Int,
    val body: String,
    val contentType: String = "application/json; charset=utf-8",
)

internal class ApiController(
    private val pairingManager: PairingManager,
    private val tokenStore: TokenStore,
    private val idempotencyStore: IdempotencyStore,
    private val coordinator: PlaybackCoordinator,
    private val dispatcher: CallDispatcher,
    private val security: RequestSecurity,
    private val assetLoader: (String) -> String,
    private val playlists: PlaylistApi? = null,
    private val prepared: dev.siyovush.parentcast.tv.prepared.PreparedApi? = null,
    private val connectUrls: (() -> List<String>)? = null,
    private val displayReady: (() -> Boolean)? = null,
    private val localMedia: dev.siyovush.parentcast.tv.local.LocalVideoApi? = null,
    private val mediaTitle: (String) -> String = { "" },
) {
    fun handle(request: ApiRequest): ApiResponse {
        if (!security.isHostAllowed(request.headers["host"])) {
            return ApiResponse(421, error("MISDIRECTED_HOST"))
        }
        if (request.body.length > 65_536) return ApiResponse(413, error("BODY_TOO_LARGE"))
        if (request.method == "OPTIONS") return ApiResponse(403, error("FORBIDDEN"))
        return when {
            request.method == "GET" && request.path == "/" -> staticAsset("web/index.html", "text/html; charset=utf-8")
            request.method == "GET" && request.path == "/app.js" -> staticAsset("web/app.js", "text/javascript; charset=utf-8")
            request.method == "GET" && request.path == "/media.js" -> staticAsset("web/media.js", "text/javascript; charset=utf-8")
            request.method == "GET" && request.path == "/style.css" -> staticAsset("web/style.css", "text/css; charset=utf-8")
            request.method == "GET" && request.path == "/api/health" -> ApiResponse(200, JSONObject().put("ok", true).put("version", 1).toString())
            request.method == "POST" && request.path == "/api/pair" -> pair(request)
            else -> authorizedApi(request)
        }
    }

    /** Authenticate before reading an arbitrary-size media body. Never use the JSON body parser. */
    fun upload(request: ApiRequest, length: Long, input: java.io.InputStream): ApiResponse {
        if(!security.isHostAllowed(request.headers["host"])) return ApiResponse(421,error("MISDIRECTED_HOST"))
        val device=authorizedDevice(request.headers) ?: return ApiResponse(401,error("UNAUTHORIZED"))
        if(request.method!="POST" || !security.isStateChangeAllowed(request.headers)) return ApiResponse(403,error("FORBIDDEN"))
        if(length !in 1..dev.siyovush.parentcast.tv.prepared.LIBRARY_LIMIT || request.headers.containsKey("transfer-encoding")) return ApiResponse(413,error("BODY_TOO_LARGE"))
        if(request.headers["content-type"]?.substringBefore(';')?.trim()!="application/octet-stream") return ApiResponse(415,error("BAD_MEDIA_INPUT"))
        val id=request.path.removePrefix("/api/local/upload/")
        if(!id.matches(Regex("[A-Za-z0-9-]{36}"))) return ApiResponse(400,error("BAD_REQUEST"))
        return localMedia?.upload(id,device.deviceId,length,input) ?: ApiResponse(404,error("NOT_FOUND"))
    }

    private fun pair(request: ApiRequest): ApiResponse {
        if (!security.isStateChangeAllowed(request.headers)) return ApiResponse(403, error("FORBIDDEN"))
        val json = request.json() ?: return ApiResponse(400, error("BAD_REQUEST"))
        val result = pairingManager.completePairing(
            pin = json.optString("pin"),
            label = json.optString("label", "Browser").take(40),
            clientKey = request.clientKey,
        ) ?: return ApiResponse(401, error("PAIRING_FAILED"))
        return ApiResponse(
            200,
            JSONObject()
                .put("deviceId", result.first.deviceId)
                .put("token", result.second)
                .toString(),
        )
    }

    private fun authorizedApi(request: ApiRequest): ApiResponse {
        val device = authorizedDevice(request.headers) ?: return ApiResponse(401, error("UNAUTHORIZED"))
        if (request.method == "GET" && request.path == "/api/state") return ApiResponse(200, stateBody())
        if (request.method == "GET" && request.path == "/api/prepared") return ApiResponse(200, prepared?.state()?.toString() ?: "{}")
        if (request.method == "GET" && request.path == "/api/devices") return devices()
        if (request.method == "GET") {
            localMedia?.read(request.path, device.deviceId)?.let { return it }
            playlists?.read(request.path, device.deviceId)?.let { return it }
        }
        if (request.method != "POST") return ApiResponse(404, error("NOT_FOUND"))
        if (!security.isStateChangeAllowed(request.headers)) return ApiResponse(403, error("FORBIDDEN"))
        return when (request.path) {
            "/api/prepared" -> mutation(request, device) { json -> prepared?.mutate(json) ?: (404 to error("NOT_FOUND")) }
            "/api/queue/add" -> mutation(request, device) { json -> add(json, commandStorageKey(request, device, json), requestFingerprint(json)) }
            "/api/queue/play-now" -> mutation(request, device) { json -> playNow(json) }
            "/api/playback" -> mutation(request, device) { json -> playback(json) }
            "/api/queue/delete" -> mutation(request, device) { json -> deletePending(json) }
            "/api/queue/move" -> mutation(request, device) { json -> movePending(json) }
            "/api/queue/select" -> mutation(request, device) { json ->
                try {
                    if(json.optBoolean("play") && displayReady?.invoke()==false) 409 to error("OPEN_PLAYER_ON_TV")
                    else when(dispatcher.call {coordinator.selectQueued(json.optString("queueItemId"),json.optLong("queueVersion",-1),json.optLong("positionMs",0),json.optBoolean("play"),commandStorageKey(request,device,json),requestFingerprint(json))}) {
                        dev.siyovush.parentcast.tv.queue.BatchAddResult.APPLIED,dev.siyovush.parentcast.tv.queue.BatchAddResult.REPLAY -> 200 to stateBody()
                        dev.siyovush.parentcast.tv.queue.BatchAddResult.CONFLICT -> conflict()
                        dev.siyovush.parentcast.tv.queue.BatchAddResult.KEY_REUSED -> 409 to error("IDEMPOTENCY_KEY_REUSED")
                        else -> 503 to error("COMMAND_STORAGE_ERROR")
                    }
                } catch (_:IllegalArgumentException){badRequest()}
            }
            "/api/queue/reorder" -> mutation(request, device) { json ->
                try {
                    val a=json.getJSONArray("queueItemIds");require(a.length()<=10_000)
                    val ids=List(a.length()) { a.getString(it) }
                    when(dispatcher.call { coordinator.reorderPending(ids,json.optLong("queueVersion",-1),commandStorageKey(request,device,json),requestFingerprint(json)) }) {
                        dev.siyovush.parentcast.tv.queue.BatchAddResult.APPLIED,dev.siyovush.parentcast.tv.queue.BatchAddResult.REPLAY -> 200 to stateBody()
                        dev.siyovush.parentcast.tv.queue.BatchAddResult.CONFLICT -> conflict()
                        dev.siyovush.parentcast.tv.queue.BatchAddResult.KEY_REUSED -> 409 to error("IDEMPOTENCY_KEY_REUSED")
                        dev.siyovush.parentcast.tv.queue.BatchAddResult.RECEIPT_LIMIT -> 429 to error("COMMAND_STORAGE_LIMIT")
                        else -> 503 to error("COMMAND_STORAGE_ERROR")
                    }
                } catch (_: IllegalArgumentException) { badRequest() }
                  catch (_: org.json.JSONException) { badRequest() }
                  catch (_: Exception) { 503 to error("COMMAND_STORAGE_ERROR") }
            }
            "/api/queue/clear-all" -> mutation(request, device) { json ->
                try {
                    if (dispatcher.call { coordinator.clearAll(json.optLong("queueVersion", -1)) }) 200 to stateBody() else conflict()
                } catch (_: Exception) { 503 to error("COMMAND_STORAGE_ERROR") }
            }
            "/api/queue/clear" -> mutation(request, device) { json -> clearPending(json) }
            "/api/devices/revoke" -> mutation(request, device) { json -> revoke(json) }
            else -> if(localMedia?.handles(request.path)==true) {
                mutation(request,device) { json -> localMedia.mutate(request.path,device.deviceId,json,commandStorageKey(request,device,json),requestFingerprint(json)) }
            } else if (playlists?.handles(request.path) == true) {
                mutation(request, device) { json ->
                    playlists.mutate(request.path, device.deviceId, json, commandStorageKey(request, device, json), requestFingerprint(json)).let { it.code to it.body }
                }
            } else ApiResponse(404, error("NOT_FOUND"))
        }
    }

    private fun authorizedDevice(headers: Map<String, String>): DeviceRecord? {
        val authorization = headers["authorization"] ?: return null
        if (!authorization.startsWith("Bearer ")) return null
        return tokenStore.verify(authorization.removePrefix("Bearer ").trim())
    }

    @Synchronized
    private fun mutation(
        request: ApiRequest,
        device: DeviceRecord,
        action: (JSONObject) -> Pair<Int, String>,
    ): ApiResponse {
        val json = request.json() ?: return ApiResponse(400, error("BAD_REQUEST"))
        val key = json.optString("idempotencyKey")
        if (key.length < 8 || key.length > 100) return ApiResponse(400, error("BAD_REQUEST"))
        val storageKey = commandStorageKey(request, device, json)
        val fingerprint = if (playlists?.handles(request.path) == true || localMedia?.handles(request.path)==true || request.path in setOf("/api/queue/add", "/api/prepared", "/api/queue/clear-all", "/api/queue/reorder", "/api/queue/select")) requestFingerprint(json) else null
        idempotencyStore.get(storageKey)?.let {
            val legacyAdd = request.path == "/api/queue/add" && it.requestFingerprint == null
            if (fingerprint != null && !legacyAdd && it.requestFingerprint != fingerprint) return ApiResponse(409, error("IDEMPOTENCY_KEY_REUSED"))
            return ApiResponse(it.code, it.body)
        }
        val result = action(json)
        if (fingerprint == null || (result.first < 500 && result.first != 429)) {
            try {
                idempotencyStore.put(storageKey, StoredResponse(result.first, result.second, System.currentTimeMillis(), fingerprint))
            } catch (_: Exception) {
                // Batch receipt already lives in the same atomic file as queue changes.
                return ApiResponse(503, error("COMMAND_STORAGE_ERROR"))
            }
        }
        return ApiResponse(result.first, result.second)
    }

    private fun commandStorageKey(request: ApiRequest, device: DeviceRecord, json: JSONObject): String =
        "${device.deviceId}|${request.path}|${json.optString("idempotencyKey")}"

    private fun add(json: JSONObject, key: String, fingerprint: String): Pair<Int, String> {
        val links = json.optJSONArray("links") ?: return badRequest()
        if (links.length() == 0 || links.length() > 20) return badRequest()
        val videoIds = try {
            List(links.length()) { index -> dev.siyovush.parentcast.tv.media.OnlineMediaIds.parseLink(links.getString(index)) }
        } catch (error: Exception) {
            return badRequest()
        }
        if (json.has("queueVersion")) {
            val version = json.get("queueVersion")
            if ((version !is Int && version !is Long) || (version as Number).toLong() < 0) return badRequest()
        }
        val (result, addition) = dispatcher.call {
            val version = json.optLong("queueVersion", coordinator.currentSnapshot().queueState.version)
            coordinator.addBatch(videoIds, version, key, fingerprint) to coordinator.additionSummary(key)
        }
        return queueAdditionResponse(result, addition, stateBody()).let { it.code to it.body }
    }

    private fun playNow(json: JSONObject): Pair<Int, String> {
        if (displayReady?.invoke() == false) return 409 to error("OPEN_PLAYER_ON_TV")
        val videoId = try {
            dev.siyovush.parentcast.tv.media.OnlineMediaIds.parseLink(json.optString("videoId"))
        } catch (error: Exception) {
            return badRequest()
        }
        dispatcher.call { coordinator.playNow(videoId) }
        return 200 to stateBody()
    }

    private fun playback(json: JSONObject): Pair<Int, String> {
        val action = json.optString("action")
        if (action !in setOf("PLAY", "PAUSE_RESUME", "NEXT", "STOP")) return badRequest()
        val intent = dispatcher.call { coordinator.currentSnapshot().queueState.intent }
        val startsPlayback = action == "PLAY" || (action == "PAUSE_RESUME" && intent != dev.siyovush.parentcast.tv.queue.PlaybackIntent.PLAY) ||
            (action == "NEXT" && intent == dev.siyovush.parentcast.tv.queue.PlaybackIntent.PLAY)
        if (startsPlayback && displayReady?.invoke() == false) return 409 to error("OPEN_PLAYER_ON_TV")
        dispatcher.call {
            when (action) {
                "PLAY" -> coordinator.play()
                "PAUSE_RESUME" -> coordinator.pauseResume()
                "NEXT" -> coordinator.next()
                "STOP" -> coordinator.stop()
            }
        }
        return 200 to stateBody()
    }

    private fun deletePending(json: JSONObject): Pair<Int, String> = withVersion(json) {
        dispatcher.call { coordinator.deletePending(json.optString("queueItemId")) }
        200 to stateBody()
    }

    private fun movePending(json: JSONObject): Pair<Int, String> = withVersion(json) {
        dispatcher.call { coordinator.movePending(json.optString("queueItemId"), json.optString("direction") == "UP") }
        200 to stateBody()
    }

    private fun clearPending(json: JSONObject): Pair<Int, String> = withVersion(json) {
        dispatcher.call { coordinator.clearPending() }
        200 to stateBody()
    }

    private fun withVersion(json: JSONObject, action: () -> Pair<Int, String>): Pair<Int, String> {
        val expected = json.optLong("queueVersion", -1)
        val current = dispatcher.call { coordinator.currentSnapshot().queueState.version }
        return if (expected != current) conflict() else action()
    }

    private fun revoke(json: JSONObject): Pair<Int, String> {
        val revoked = tokenStore.revoke(json.optString("deviceId"))
        return if (revoked) {
            200 to JSONObject().put("revoked", true).toString()
        } else {
            404 to error("DEVICE_NOT_FOUND")
        }
    }

    private fun devices(): ApiResponse {
        val items = JSONArray()
        tokenStore.list().forEach { device ->
            items.put(
                JSONObject()
                    .put("deviceId", device.deviceId)
                    .put("label", device.label)
                    .put("createdAtMs", device.createdAtMs)
                    .put("revokedAtMs", device.revokedAtMs),
            )
        }
        return ApiResponse(200, JSONObject().put("devices", items).toString())
    }

    internal fun stateBody(): String {
        val snapshot = dispatcher.call { coordinator.currentSnapshot() }
        return snapshotJson(snapshot).apply {
            prepared?.let { put("prepared", it.state()) }
            connectUrls?.let { put("connectUrls", JSONArray(it())) }
            displayReady?.let { put("displayReady", it()) }
        }.toString()
    }

    private fun snapshotJson(snapshot: CoordinatorSnapshot): JSONObject {
        val items = JSONArray()
        snapshot.queueState.items.forEach { item ->
            items.put(
                JSONObject()
                    .put("queueItemId", item.queueItemId)
                    .put("videoId", item.videoId).put("local",item.local).put("title",mediaTitle(item.videoId)),
            )
        }
        val error = snapshot.error?.let { failure ->
            JSONObject()
                .put("kind", failure.kind.name)
                .put("diagnostic", failure.diagnostic)
        }
        return JSONObject()
            .put("queueVersion", snapshot.queueState.version)
            .put("items", items)
            .put("currentIndex", snapshot.queueState.currentIndex)
            .put("positionMs", snapshot.queueState.positionMs)
            .put("intent", snapshot.queueState.intent.name)
            .put("finished", snapshot.queueState.finished)
            .put("status", snapshot.status.name)
            .put("localPlayback", snapshot.localPlayback)
            .put("currentTitle", snapshot.currentTitle)
            .put("diagnostics", snapshot.diagnostics)
            .put("error", error ?: JSONObject.NULL)
            .put("updatedAtMs", snapshot.updatedAtMs)
            .put("serverTimeMs", System.currentTimeMillis())
    }

    private fun staticAsset(path: String, contentType: String): ApiResponse = try {
        ApiResponse(200, assetLoader(path), contentType)
    } catch (error: Exception) {
        ApiResponse(404, error("NOT_FOUND"))
    }

    private fun conflict(): Pair<Int, String> = 409 to JSONObject()
        .put("error", "QUEUE_VERSION_CONFLICT")
        .put("queueVersion", dispatcher.call { coordinator.currentSnapshot().queueState.version })
        .toString()

    private fun badRequest(): Pair<Int, String> = 400 to error("BAD_REQUEST")

    private fun error(code: String): String = JSONObject().put("error", code).toString()

    private fun ApiRequest.json(): JSONObject? = try {
        JSONObject(body)
    } catch (error: Exception) {
        null
    }
}

internal fun requestFingerprint(json: JSONObject): String {
    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
    return java.security.MessageDigest.getInstance("SHA-256").digest(canonical(json).toByteArray())
        .joinToString("") { "%02x".format(it) }
}

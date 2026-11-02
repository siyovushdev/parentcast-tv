package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.playback.CoordinatorSnapshot
import dev.siyovush.parentcast.tv.playback.PlaybackCoordinator
import dev.siyovush.parentcast.tv.queue.IdempotencyStore
import dev.siyovush.parentcast.tv.queue.StoredResponse
import dev.siyovush.parentcast.tv.youtube.YouTubeVideoIdParser
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
            request.method == "GET" && request.path == "/style.css" -> staticAsset("web/style.css", "text/css; charset=utf-8")
            request.method == "GET" && request.path == "/api/health" -> ApiResponse(200, JSONObject().put("ok", true).put("version", 1).toString())
            request.method == "POST" && request.path == "/api/pair" -> pair(request)
            else -> authorizedApi(request)
        }
    }

    private fun pair(request: ApiRequest): ApiResponse {
        if (!security.isStateChangeAllowed(request.headers)) return ApiResponse(403, error("FORBIDDEN"))
        val json = request.json() ?: return ApiResponse(400, error("BAD_REQUEST"))
        val result = pairingManager.completePairing(
            code = json.optString("code"),
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
        if (request.method == "GET" && request.path == "/api/devices") return devices()
        if (request.method == "GET") {
            playlists?.read(request.path, device.deviceId)?.let { return it }
        }
        if (request.method != "POST") return ApiResponse(404, error("NOT_FOUND"))
        if (!security.isStateChangeAllowed(request.headers)) return ApiResponse(403, error("FORBIDDEN"))
        return when (request.path) {
            "/api/queue/add" -> mutation(request, device) { json -> add(json) }
            "/api/queue/play-now" -> mutation(request, device) { json -> playNow(json) }
            "/api/playback" -> mutation(request, device) { json -> playback(json) }
            "/api/queue/delete" -> mutation(request, device) { json -> deletePending(json) }
            "/api/queue/move" -> mutation(request, device) { json -> movePending(json) }
            "/api/queue/clear" -> mutation(request, device) { json -> clearPending(json) }
            "/api/devices/revoke" -> mutation(request, device) { json -> revoke(json) }
            else -> if (playlists?.handles(request.path) == true) {
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
        val fingerprint = if (playlists?.handles(request.path) == true) requestFingerprint(json) else null
        idempotencyStore.get(storageKey)?.let {
            if (fingerprint != null && it.requestFingerprint != fingerprint) return ApiResponse(409, error("IDEMPOTENCY_KEY_REUSED"))
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

    private fun add(json: JSONObject): Pair<Int, String> {
        val links = json.optJSONArray("links") ?: return badRequest()
        if (links.length() == 0 || links.length() > 20) return badRequest()
        val videoIds = try {
            List(links.length()) { index -> YouTubeVideoIdParser.parse(links.getString(index)) }
        } catch (error: Exception) {
            return badRequest()
        }
        dispatcher.call { videoIds.forEach(coordinator::add) }
        return 200 to stateBody()
    }

    private fun playNow(json: JSONObject): Pair<Int, String> {
        val videoId = try {
            YouTubeVideoIdParser.parse(json.optString("videoId"))
        } catch (error: Exception) {
            return badRequest()
        }
        dispatcher.call { coordinator.playNow(videoId) }
        return 200 to stateBody()
    }

    private fun playback(json: JSONObject): Pair<Int, String> {
        val action = json.optString("action")
        if (action !in setOf("PLAY", "PAUSE_RESUME", "NEXT", "STOP")) return badRequest()
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
        return snapshotJson(snapshot).toString()
    }

    private fun snapshotJson(snapshot: CoordinatorSnapshot): JSONObject {
        val items = JSONArray()
        snapshot.queueState.items.forEach { item ->
            items.put(
                JSONObject()
                    .put("queueItemId", item.queueItemId)
                    .put("videoId", item.videoId),
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

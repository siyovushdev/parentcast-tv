package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.playback.CoordinatorSnapshot
import dev.siyovush.parentcast.tv.playback.PlaybackCoordinator
import dev.siyovush.parentcast.tv.playback.PlaybackPlayer
import dev.siyovush.parentcast.tv.queue.AtomicIdempotencyStore
import dev.siyovush.parentcast.tv.queue.AtomicQueueStore
import dev.siyovush.parentcast.tv.youtube.ResolvedVideo
import dev.siyovush.parentcast.tv.youtube.StreamSpec
import dev.siyovush.parentcast.tv.youtube.YouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApiControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stateRequiresAuthorization() {
        val fixture = Fixture()
        val response = fixture.controller.handle(request("GET", "/api/state", fixture.auth(null)))
        assertEquals(401, response.code)
    }

    @Test
    fun addIsIdempotentEvenAfterRepeatedResponseLoss() {
        val fixture = Fixture()
        val token = fixture.pair()
        val key = "add-command-1"
        val body = JSONObject()
            .put("links", JSONArray().put("https://youtu.be/jNQXAC9IVRw"))
            .put("idempotencyKey", key)
            .toString()

        val first = fixture.controller.handle(request("POST", "/api/queue/add", fixture.auth(token), body))
        val second = fixture.controller.handle(request("POST", "/api/queue/add", fixture.auth(token), body))
        val third = fixture.controller.handle(request("POST", "/api/queue/add", fixture.auth(token), body))

        assertEquals(200, first.code)
        assertEquals(200, second.code)
        assertEquals(200, third.code)
        assertEquals(first.body, second.body)
        assertEquals(second.body, third.body)
        assertEquals(1, JSONObject(first.body).getJSONArray("items").length())
    }

    @Test
    fun simultaneousRepeatedAddRunsOnlyOnce() {
        val fixture = Fixture()
        val token = fixture.pair()
        val body = JSONObject()
            .put("links", JSONArray().put("https://youtu.be/jNQXAC9IVRw"))
            .put("idempotencyKey", "concurrent-add")
            .toString()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val barrier = java.util.concurrent.CyclicBarrier(8)
        try {
            val calls = List(8) {
                pool.submit<ApiResponse> {
                    barrier.await()
                    fixture.controller.handle(request("POST", "/api/queue/add", fixture.auth(token), body))
                }
            }
            val responses = calls.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(listOf(200), responses.map { it.code }.distinct())
            assertEquals(listOf(responses.first().body), responses.map { it.body }.distinct())
            assertEquals(1, JSONObject(responses.first().body).getJSONArray("items").length())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun staleQueueVersionProducesExplicitConflict() {
        val fixture = Fixture()
        val token = fixture.pair()
        fixture.add("https://youtu.be/jNQXAC9IVRw", "first-add", token)
        fixture.add("https://youtu.be/aqz-KE-bpKQ", "second-add", token)
        val staleVersion = fixture.controller.handle(request("GET", "/api/state", fixture.auth(token)))
            .let { JSONObject(it.body).getLong("queueVersion") }
        fixture.add("https://youtu.be/eRsGyueVLvQ", "third-add", token)
        val move = JSONObject()
            .put("queueItemId", fixture.pendingId(1, token))
            .put("direction", "UP")
            .put("queueVersion", staleVersion)
            .put("idempotencyKey", "move-stale")
            .toString()

        val response = fixture.controller.handle(request("POST", "/api/queue/move", fixture.auth(token), move))

        assertEquals(409, response.code)
        assertEquals("QUEUE_VERSION_CONFLICT", JSONObject(response.body).getString("error"))
    }

    @Test
    fun revokedTokenCannotReadOrMutate() {
        val fixture = Fixture()
        val token = fixture.pair()
        val deviceId = fixture.controller.handle(request("GET", "/api/devices", fixture.auth(token)))
            .let { JSONObject(it.body).getJSONArray("devices").getJSONObject(0).getString("deviceId") }
        val revoke = JSONObject()
            .put("deviceId", deviceId)
            .put("idempotencyKey", "revoke-1")
            .toString()
        assertEquals(200, fixture.controller.handle(request("POST", "/api/devices/revoke", fixture.auth(token), revoke)).code)

        assertEquals(401, fixture.controller.handle(request("GET", "/api/state", fixture.auth(token))).code)
    }

    @Test
    fun invalidPlaybackActionIsRejectedWithoutChangingQueue() {
        val fixture = Fixture()
        val token = fixture.pair()
        fixture.add("https://youtu.be/jNQXAC9IVRw", "before-invalid", token)
        val body = JSONObject()
            .put("action", "UNKNOWN")
            .put("idempotencyKey", "invalid-playback")
            .toString()

        val response = fixture.controller.handle(request("POST", "/api/playback", fixture.auth(token), body))

        assertEquals(400, response.code)
        assertEquals(1, fixture.coordinator.currentSnapshot().queueState.items.size)
    }

    @Test
    fun hostOriginAndCustomHeaderAreEnforced() {
        val fixture = Fixture()
        val token = fixture.pair()
        val evilOrigin = mapOf<String, String>(
            "host" to "192.168.68.121:8765",
            "x-parentcast-client" to "m1",
            "origin" to "https://evil.example",
            "authorization" to "Bearer $token",
        )
        val evilHost = mapOf<String, String>(
            "host" to "evil.example:8765",
            "x-parentcast-client" to "m1",
            "origin" to "http://192.168.68.121:8765",
            "authorization" to "Bearer $token",
        )
        val noCustomHeader = mapOf<String, String>(
            "host" to "192.168.68.121:8765",
            "origin" to "http://192.168.68.121:8765",
            "authorization" to "Bearer $token",
        )

        assertEquals(403, fixture.controller.handle(ApiRequest("POST", "/api/queue/clear", evilOrigin, "{}", "test-client")).code)
        assertEquals(421, fixture.controller.handle(ApiRequest("GET", "/api/state", evilHost, "", "test-client")).code)
        assertEquals(403, fixture.controller.handle(ApiRequest("POST", "/api/queue/clear", noCustomHeader, "{}", "test-client")).code)
    }

    private inner class Fixture {
        val tokenStore = AtomicTokenStore(file("devices.json"))
        val settings = object : PinSettings {
            private val record = PinSecurity.hashPin("739251")
            override fun hasPin(): Boolean = true
            override fun pinRecord(): String = record
            override fun savePinRecord(record: String) = Unit
        }
        val pairingManager = PairingManager(settings, tokenStore) { 1_000 }
        val coordinator = PlaybackCoordinator(
            source = object : YouTubeSource {
                override suspend fun resolve(videoId: String) = ResolvedVideo(
                    videoId = videoId,
                    title = "Test",
                    durationSeconds = 10,
                    video = StreamSpec("https://media.invalid/video.mp4", "video/mp4", emptyMap()),
                    audio = null,
                    selectionDescription = "360p, H.264/AAC, test",
                )
            },
            store = AtomicQueueStore(file("queue.json")),
            player = object : PlaybackPlayer {
                override fun setListener(listener: PlaybackPlayer.Listener) = Unit
                override fun prepare(generation: Long, video: ResolvedVideo, positionMs: Long, playWhenReady: Boolean) = Unit
                override fun play() = Unit
                override fun pause() = Unit
                override fun stop() = Unit
                override fun currentPositionMs(): Long = 0
                override fun release() = Unit
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val controller = ApiController(
            pairingManager = pairingManager,
            tokenStore = tokenStore,
            idempotencyStore = AtomicIdempotencyStore(file("idempotency.json")),
            coordinator = coordinator,
            dispatcher = object : CallDispatcher {
                override fun <T> call(block: () -> T): T = block()
            },
            security = RequestSecurity(setOf("192.168.68.121", "127.0.0.1"), 8765),
            assetLoader = { "<html></html>" },
        )

        fun pair(): String {
            val challenge = pairingManager.beginPairing()
            val body = JSONObject()
                .put("code", challenge.code)
                .put("pin", "739251")
                .put("label", "Test")
                .toString()
            val response = controller.handle(request("POST", "/api/pair", auth(null), body))
            assertEquals(200, response.code)
            return JSONObject(response.body).getString("token")
        }

        fun auth(token: String?): Map<String, String> = buildMap {
            put("host", "192.168.68.121:8765")
            put("x-parentcast-client", "m1")
            put("origin", "http://192.168.68.121:8765")
            put("referer", "http://192.168.68.121:8765/")
            token?.let { put("authorization", "Bearer $it") }
        }

        fun add(link: String, key: String, token: String) {
            val body = JSONObject()
                .put("links", JSONArray().put(link))
                .put("idempotencyKey", key)
                .toString()
            val response = controller.handle(request("POST", "/api/queue/add", auth(token), body))
            assertEquals(200, response.code)
        }

        fun pendingId(index: Int, token: String): String {
            val state = JSONObject(controller.handle(request("GET", "/api/state", auth(token))).body)
            return state.getJSONArray("items").getJSONObject(index).getString("queueItemId")
        }

        private fun file(name: String): File = temporaryFolder.newFile(name)
    }

    private fun request(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
    ) = ApiRequest(method, path, headers, body, "test-client")
}

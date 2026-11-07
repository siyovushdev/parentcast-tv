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
    fun legacyAddResponseRemainsReplayableAfterUpgrade() {
        val f = Fixture(); val token = f.pair()
        val body = JSONObject().put("links", JSONArray(listOf("https://youtu.be/jNQXAC9IVRw")))
            .put("idempotencyKey", "legacy-add-1").toString()
        val original = f.controller.handle(request("POST", "/api/queue/add", f.auth(token), body))
        val file = File(temporaryFolder.root, "idempotency.json")
        val stored = JSONObject(file.readText())
        stored.keys().forEach { stored.getJSONObject(it).remove("requestFingerprint") }
        file.writeText(stored.toString())
        val replay = f.controller.handle(request("POST", "/api/queue/add", f.auth(token), body))
        assertEquals(200, replay.code)
        assertEquals(original.body, replay.body)
        assertEquals(1, f.coordinator.currentSnapshot().queueState.items.size)
    }

    @Test
    fun differentLinksAndNewCommandsCannotAppendTheSameVideo() {
        val f = Fixture(); val token = f.pair()
        fun add(key: String) = f.controller.handle(request("POST", "/api/queue/add", f.auth(token), JSONObject()
            .put("links", JSONArray(listOf("https://youtu.be/jNQXAC9IVRw", "https://www.youtube.com/watch?v=jNQXAC9IVRw")))
            .put("idempotencyKey", key).toString()))
        val first = JSONObject(add("dedup-first").body)
        assertEquals(1, first.getJSONObject("addition").getInt("added"))
        assertEquals(1, first.getJSONObject("addition").getInt("skipped"))
        val second = JSONObject(add("dedup-second").body)
        assertEquals(1, second.getJSONArray("items").length())
        assertEquals(0, second.getJSONObject("addition").getInt("added"))
        assertEquals(2, second.getJSONObject("addition").getInt("skipped"))
        assertEquals(first.getLong("queueVersion"), second.getLong("queueVersion"))
    }

    @Test
    fun thumbnailsRequireAuthorizationAndValidHost() {
        val f = Fixture()
        assertEquals(401, f.controller.handle(request("GET", "/api/thumbnails/video000001", f.auth(null))).code)
        val token = f.pair()
        assertEquals(421, f.controller.handle(request("GET", "/api/thumbnails/video000001", f.auth(token) + ("host" to "evil.test:8765"))).code)
        assertEquals(404, f.controller.handle(request("GET", "/api/thumbnails/video000001", f.auth(token))).code)
        val devices = JSONObject(f.controller.handle(request("GET", "/api/devices", f.auth(token))).body).getJSONArray("devices")
        f.controller.handle(request("POST", "/api/devices/revoke", f.auth(token), JSONObject().put("deviceId",devices.getJSONObject(0).getString("deviceId")).put("idempotencyKey","thumbnail-revoke").toString()))
        assertEquals(401, f.controller.handle(request("GET", "/api/thumbnails/video000001", f.auth(token))).code)
    }

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

    @Test
    fun pinOnlyLoginDoesNotRequireTvActionOrAuthorizePinAsBearer() {
        val fixture = Fixture()
        val before = fixture.coordinator.currentSnapshot().queueState
        val response = fixture.controller.handle(request("POST", "/api/pair", fixture.auth(null), "{\"pin\":\"739251\"}"))
        assertEquals(200, response.code)
        val token = JSONObject(response.body).getString("token")

        assertNotEquals("739251", token)
        assertEquals("Browser", fixture.tokenStore.verify(token)!!.label)
        assertEquals(200, fixture.controller.handle(request("GET", "/api/state", fixture.auth(token))).code)
        assertEquals(401, fixture.controller.handle(request("GET", "/api/state", fixture.auth("739251"))).code)
        assertEquals(before, fixture.coordinator.currentSnapshot().queueState)
    }

    @Test
    fun newLoginKeepsStoredTokensAndRevocationIsIndependent() {
        val fixture = Fixture()
        val existing = fixture.tokenStore.create("Existing browser").second
        val first = fixture.pair()
        val second = fixture.pair()
        assertNotEquals(existing, first)
        assertNotEquals(first, second)
        val reloaded = AtomicTokenStore(fixture.tokenFile)
        listOf(existing, first, second).forEach { assertTrue(reloaded.verify(it) != null) }

        val revoke = JSONObject().put("deviceId", reloaded.verify(second)!!.deviceId)
            .put("idempotencyKey", "revoke-new-browser").toString()
        assertEquals(200, fixture.controller.handle(request("POST", "/api/devices/revoke", fixture.auth(existing), revoke)).code)
        assertEquals(401, fixture.controller.handle(request("GET", "/api/state", fixture.auth(second))).code)
        listOf(existing, first).forEach {
            assertEquals(200, fixture.controller.handle(request("GET", "/api/state", fixture.auth(it))).code)
        }
    }

    @Test
    fun pinOnlyLoginCountsFailuresAndLeavesExistingTokenUsable() {
        val fixture = Fixture()
        val existing = fixture.tokenStore.create("Existing browser").second
        val wrong = JSONObject().put("pin", "000000").toString()
        repeat(5) {
            assertEquals(401, fixture.controller.handle(request("POST", "/api/pair", fixture.auth(null), wrong)).code)
        }
        val correct = JSONObject().put("pin", "739251").toString()
        assertEquals(401, fixture.controller.handle(request("POST", "/api/pair", fixture.auth(null), correct)).code)
        assertEquals(1, fixture.tokenStore.list().size)
        assertEquals(200, fixture.controller.handle(request("GET", "/api/state", fixture.auth(existing))).code)
    }

    @Test
    fun pinOnlyLoginRejectsMissingOrMalformedPinWithoutIssuingTokens() {
        val fixture = Fixture()
        listOf("{}", "{\"pin\":\"\"}", "{\"pin\":\"123\"}", "{\"pin\":\"abcdef\"}").forEach { body ->
            assertEquals(401, fixture.controller.handle(request("POST", "/api/pair", fixture.auth(null), body)).code)
        }
        assertTrue(fixture.tokenStore.list().isEmpty())
    }

    @Test
    fun pinOnlyLoginKeepsHostOriginRefererAndCustomHeaderGuards() {
        val fixture = Fixture()
        val headers = fixture.auth(null)
        val body = JSONObject().put("pin", "739251").toString()
        val rejected = listOf(
            (headers + ("host" to "evil.example:8765")) to 421,
            (headers + ("origin" to "https://evil.example")) to 403,
            (headers + ("referer" to "https://evil.example/")) to 403,
            (headers - "x-parentcast-client") to 403,
        )
        rejected.forEach { (requestHeaders, status) ->
            assertEquals(status, fixture.controller.handle(request("POST", "/api/pair", requestHeaders, body)).code)
        }
        assertEquals(403, fixture.controller.handle(request("OPTIONS", "/api/pair", headers)).code)
        assertTrue(fixture.tokenStore.list().isEmpty())
    }

    @Test
    fun preparationUsesAuthenticationCsrfVersionAndCommandFingerprint() {
        val f = Fixture()
        assertEquals(401, f.controller.handle(request("GET", "/api/prepared", f.auth(null))).code)
        val token = f.pair()
        f.add("https://youtu.be/jNQXAC9IVRw", "prepared-add", token)
        val queue = JSONObject(f.controller.stateBody()); val version = queue.getLong("queueVersion")
        val body = JSONObject().put("action", "ENABLE").put("queueVersion", version).put("idempotencyKey", "prepare-key-1")
        assertEquals(421, f.controller.handle(request("POST", "/api/prepared", f.auth(token) + ("host" to "evil.invalid:8765"), body.toString())).code)
        assertEquals(403, f.controller.handle(request("POST", "/api/prepared", f.auth(token) + ("origin" to "http://evil.invalid"), body.toString())).code)
        val stale = JSONObject(body.toString()).put("queueVersion", version - 1).put("idempotencyKey", "prepare-stale")
        assertEquals(409, f.controller.handle(request("POST", "/api/prepared", f.auth(token), stale.toString())).code)
        val first = f.controller.handle(request("POST", "/api/prepared", f.auth(token), body.toString()))
        assertEquals(200, first.code)
        assertEquals(first, f.controller.handle(request("POST", "/api/prepared", f.auth(token), body.toString())))
        body.put("action", "DISABLE")
        assertEquals(409, f.controller.handle(request("POST", "/api/prepared", f.auth(token), body.toString())).code)
        val after = JSONObject(f.controller.stateBody())
        assertEquals(version, after.getLong("queueVersion"))
        assertEquals(queue.getJSONArray("items").toString(), after.getJSONArray("items").toString())
        assertEquals(queue.getString("intent"), after.getString("intent"))
        assertTrue(after.getJSONObject("prepared").getBoolean("enabled"))
    }

    @Test
    fun fullClearChecksVersionStopsAndDoesNotRepeatOnNewItems() {
        val f = Fixture(); val token = f.pair()
        f.add("https://youtu.be/jNQXAC9IVRw", "clear-first-add", token)
        val before = f.coordinator.currentSnapshot().queueState
        val command = JSONObject().put("queueVersion", before.version - 1).put("idempotencyKey", "clear-stale-1")
        fun clear() = f.controller.handle(request("POST", "/api/queue/clear-all", f.auth(token), command.toString()))
        assertEquals(409, clear().code)
        assertEquals(before, f.coordinator.currentSnapshot().queueState)
        command.put("queueVersion", before.version).put("idempotencyKey", "clear-full-once")
        val first = clear()
        assertEquals(200, first.code)
        val empty = f.coordinator.currentSnapshot().queueState
        assertTrue(empty.items.isEmpty())
        assertEquals(dev.siyovush.parentcast.tv.queue.PlaybackIntent.STOP, empty.intent)
        assertEquals(before.version + 1, empty.version)
        f.add("https://youtu.be/aqz-KE-bpKQ", "clear-second-add", token)
        assertEquals(first, clear())
        assertEquals(listOf("aqz-KE-bpKQ"), f.coordinator.currentSnapshot().queueState.items.map { it.videoId })
        command.put("queueVersion", empty.version)
        assertEquals("IDEMPOTENCY_KEY_REUSED", JSONObject(clear().body).getString("error"))
        assertTrue(f.tokenStore.verify(token) != null)
    }

    @Test
    fun deletionChecksVersionExplicitStopAndKeepsQueueAndTokens() {
        val f = Fixture(); val token = f.pair()
        f.add("https://youtu.be/jNQXAC9IVRw", "delete-add-first", token)
        val before = f.coordinator.currentSnapshot().queueState
        val command = JSONObject().put("action", "DELETE").put("videoId", "jNQXAC9IVRw")
            .put("queueVersion", before.version - 1).put("idempotencyKey", "delete-stale-one")
        fun send() = f.controller.handle(request("POST", "/api/prepared", f.auth(token), command.toString()))
        assertEquals(409, send().code)
        command.put("queueVersion", before.version).put("idempotencyKey", "delete-without-stop")
        assertEquals("FILE_IN_USE", JSONObject(send().body).getString("error"))
        assertEquals(before, f.coordinator.currentSnapshot().queueState)
        command.put("stopPlayback", true).put("idempotencyKey", "delete-with-stop")
        val first = send()
        assertEquals(202, first.code)
        assertEquals(first, send())
        assertEquals(before.items, f.coordinator.currentSnapshot().queueState.items)
        assertEquals(dev.siyovush.parentcast.tv.queue.PlaybackIntent.STOP, f.coordinator.currentSnapshot().queueState.intent)
        assertTrue(f.tokenStore.verify(token) != null)
        command.put("action", "DELETE_ALL").put("queueVersion", f.coordinator.currentSnapshot().queueState.version)
            .put("stopPlayback", false).put("idempotencyKey", "delete-all-no-stop")
        assertEquals("FILE_IN_USE", JSONObject(send().body).getString("error"))
        command.put("stopPlayback", true).put("idempotencyKey", "delete-all-stopped")
        assertEquals(202, send().code)
        command.put("action", "DELETE").put("videoId", "../../devices.json").put("idempotencyKey", "delete-bad-path")
        assertEquals(400, send().code)
    }

    @Test
    fun backgroundPanelAllowsQueueAndStopButDoesNotStartInvisiblePlayback() {
        val f = Fixture(displayReady = false); val token = f.pair()
        f.add("https://youtu.be/jNQXAC9IVRw", "background-add", token)
        val before = f.coordinator.currentSnapshot().queueState
        val play = JSONObject().put("action", "PLAY").put("idempotencyKey", "background-play")
        val response = f.controller.handle(request("POST", "/api/playback", f.auth(token), play.toString()))
        assertEquals(409, response.code)
        assertEquals("OPEN_PLAYER_ON_TV", JSONObject(response.body).getString("error"))
        assertEquals(before, f.coordinator.currentSnapshot().queueState)
        play.put("videoId", "https://youtu.be/aqz-KE-bpKQ").put("idempotencyKey", "background-play-now")
        assertEquals(409, f.controller.handle(request("POST", "/api/queue/play-now", f.auth(token), play.toString())).code)
        play.put("action", "STOP").put("idempotencyKey", "background-stop")
        assertEquals(200, f.controller.handle(request("POST", "/api/playback", f.auth(token), play.toString())).code)
        assertEquals(false, JSONObject(f.controller.stateBody()).getBoolean("displayReady"))
    }

    private inner class Fixture(displayReady: Boolean = true) {
        val tokenFile = file("devices.json")
        val tokenStore = AtomicTokenStore(tokenFile)
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
        val callDispatcher = object : CallDispatcher { override fun <T> call(block: () -> T): T = block() }
        val library = dev.siyovush.parentcast.tv.prepared.PreparedLibrary(
            dev.siyovush.parentcast.tv.prepared.PreparedStore(temporaryFolder.newFolder()),
            object : YouTubeSource { override suspend fun resolve(videoId: String): ResolvedVideo = error("No implicit download/play") },
            dev.siyovush.parentcast.tv.prepared.TrackDownloader { _, _, _, _ -> error("No implicit download") },
            CoroutineScope(Dispatchers.Unconfined), { _, _, _ -> })
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
            prepared = dev.siyovush.parentcast.tv.prepared.PreparedApi(library, coordinator, callDispatcher),
            displayReady = { displayReady },
        )

        fun pair(): String {
            val body = JSONObject()
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

package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.collections.AtomicCollectionStore
import dev.siyovush.parentcast.tv.playback.PlaybackCoordinator
import dev.siyovush.parentcast.tv.playback.PlaybackPlayer
import dev.siyovush.parentcast.tv.playlist.*
import dev.siyovush.parentcast.tv.queue.AtomicIdempotencyStore
import dev.siyovush.parentcast.tv.queue.AtomicQueueStore
import dev.siyovush.parentcast.tv.youtube.ResolvedVideo
import dev.siyovush.parentcast.tv.youtube.YouTubeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Deterministic fixture tests; these do not contact YouTube or prove hardware playback. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistApiTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun playlistThenSavedCollectionAndAnotherBrowserCannotDuplicateTheQueue() = runTest {
        val f = Fixture(this); val token = f.pair(); val other = f.pair()
        val id = f.preview(token); runCurrent()
        val saved = f.post(token, "/api/collections/save", JSONObject().put("previewId", id)
            .put("indices", JSONArray(listOf(1,2))).put("name", "Programme"))
        val collection = JSONObject(saved.body).getJSONObject("collection").getString("id")
        val first = f.apply(token,id,listOf(1,2))
        assertEquals(2,JSONObject(first.body).getJSONObject("addition").getInt("added"))
        val repeated = f.post(other,"/api/collections/apply",JSONObject().put("collectionId",collection).put("queueVersion",1))
        assertEquals(200,repeated.code)
        assertEquals(0,JSONObject(repeated.body).getJSONObject("addition").getInt("added"))
        assertEquals(2,JSONObject(repeated.body).getJSONObject("addition").getInt("skipped"))
        assertEquals(listOf("video000001","video000002"),f.videoIds())
        assertEquals(2L,f.coordinator.currentSnapshot().queueState.version)
        assertEquals(2,f.coordinator.currentSnapshot().queueState.groups.size)
        assertEquals(0,f.playerCalls)
        f.close()
    }

    @Test
    fun previewIsOwnerScopedAndDoesNotChangeQueue() = runTest {
        val fixture = Fixture(this)
        val first = fixture.pair()
        val second = fixture.pair()
        val id = fixture.preview(first)
        runCurrent()

        assertEquals(200, fixture.get(first, "/api/playlists/preview/$id").code)
        assertEquals(404, fixture.get(second, "/api/playlists/preview/$id").code)
        assertEquals(404, fixture.post(second, "/api/playlists/cancel", JSONObject().put("previewId", id)).code)
        assertEquals(404, fixture.apply(second, id, listOf(1)).code)
        assertTrue(fixture.coordinator.currentSnapshot().queueState.items.isEmpty())
        fixture.close()
    }

    @Test
    fun selectedIndicesAreOneBasedValidatedAndAppliedInOriginalOrder() = runTest {
        val fixture = Fixture(this)
        fixture.result = fixture.result.copy(entries = listOf(entry(1), entry(2, false), entry(3)))
        val token = fixture.pair()
        val id = fixture.preview(token)
        runCurrent()
        listOf(listOf(0), listOf(2), listOf(4), listOf(1, 1), emptyList()).forEach { indices ->
            assertEquals(400, fixture.apply(token, id, indices).code)
            assertTrue(fixture.coordinator.currentSnapshot().queueState.items.isEmpty())
        }
        assertEquals(200, fixture.apply(token, id, listOf(3, 1)).code)
        assertEquals(listOf("video000001", "video000003"), fixture.videoIds())
        assertEquals(1L, fixture.coordinator.currentSnapshot().queueState.version)
        assertEquals(0, fixture.playerCalls)
        fixture.close()
    }

    @Test
    fun partialPreviewPreservesWarningAndAllowsOnlyExplicitSelection() = runTest {
        val fixture = Fixture(this)
        fixture.result = fixture.result.copy(status = PlaylistStatus.PARTIAL, reason = "Страница 2 недоступна", reportedCount = 250)
        val token = fixture.pair()
        val id = fixture.preview(token)
        runCurrent()
        val response = JSONObject(fixture.get(token, "/api/playlists/preview/$id").body)
        assertEquals("PARTIAL", response.getString("status"))
        assertEquals("Страница 2 недоступна", response.getString("reason"))
        assertTrue(fixture.videoIds().isEmpty())
        assertEquals(200, fixture.apply(token, id, listOf(1)).code)
        assertEquals(listOf("video000001"), fixture.videoIds())
        fixture.close()
    }

    @Test
    fun limitExceededAndCancelledPreviewsCannotBeApplied() = runTest {
        val fixture = Fixture(this)
        fixture.result = fixture.result.copy(status = PlaylistStatus.LIMIT_EXCEEDED, reportedCount = 501)
        val token = fixture.pair()
        val limited = fixture.preview(token)
        runCurrent()
        assertEquals(409, fixture.apply(token, limited, listOf(1)).code)
        fixture.result = fixture.result.copy(status = PlaylistStatus.COMPLETE)
        val cancelled = fixture.preview(token)
        runCurrent()
        assertEquals(200, fixture.post(token, "/api/playlists/cancel", JSONObject().put("previewId", cancelled)).code)
        assertEquals(409, fixture.apply(token, cancelled, listOf(1)).code)
        assertTrue(fixture.videoIds().isEmpty())
        fixture.close()
    }

    @Test
    fun changedQueueVersionIsAnExplicitConflictWithoutPartialAppend() = runTest {
        val fixture = Fixture(this)
        val token = fixture.pair()
        val id = fixture.preview(token)
        runCurrent()
        fixture.coordinator.add("video000099")
        val response = fixture.apply(token, id, listOf(1), version = 0)
        assertEquals(409, response.code)
        assertEquals("QUEUE_VERSION_CONFLICT", JSONObject(response.body).getString("error"))
        assertEquals(listOf("video000099"), fixture.videoIds())
        fixture.close()
    }

    @Test
    fun concurrentImportsFromSameVersionHaveOneWinner() = runTest {
        val fixture = Fixture(this)
        val first = fixture.pair()
        val second = fixture.pair()
        val id1 = fixture.preview(first)
        val id2 = fixture.preview(second)
        runCurrent()
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val calls = listOf(first to id1, second to id2).mapIndexed { i, (token, id) ->
                pool.submit<ApiResponse> { barrier.await(); fixture.apply(token, id, listOf(1), key = "concurrent-$i") }
            }
            assertEquals(listOf(200, 409), calls.map { it.get(10, TimeUnit.SECONDS).code }.sorted())
            assertEquals(listOf("video000001"), fixture.videoIds())
        } finally {
            pool.shutdownNow()
            fixture.close()
        }
    }

    @Test
    fun repeatedCommandSurvivesLostResponseAndProcessRestartWithoutPreview() = runTest {
        val fixture = Fixture(this)
        val token = fixture.pair()
        val id = fixture.preview(token)
        runCurrent()
        assertEquals(200, fixture.apply(token, id, listOf(1, 2), key = "retry-after-restart").code)
        // Simulate crash after atomic queue commit but before HTTP response-store commit.
        fixture.close()
        File(fixture.directory, "responses.json").delete()
        val restarted = Fixture(this, fixture.directory)
        assertEquals(200, restarted.apply(token, id, listOf(1, 2), key = "retry-after-restart").code)
        assertEquals(listOf("video000001", "video000002"), restarted.videoIds())
        assertEquals(1L, restarted.coordinator.currentSnapshot().queueState.version)
        assertEquals(409, restarted.apply(token, id, listOf(1), key = "retry-after-restart").code)
        restarted.close()
    }

    @Test
    fun savedProgramKeepsFixedOrderAfterSourceChangesAndCanBeAddedAndDeleted() = runTest {
        val fixture = Fixture(this)
        val token = fixture.pair()
        val preview = fixture.preview(token)
        runCurrent()
        val saved = fixture.post(token, "/api/collections/save", JSONObject()
            .put("previewId", preview).put("indices", JSONArray(listOf(2, 1))).put("name", "Вечер"))
        assertEquals(200, saved.code)
        val collectionId = JSONObject(saved.body).getJSONObject("collection").getString("id")
        assertTrue(fixture.videoIds().isEmpty())
        fixture.result = fixture.result.copy(entries = listOf(entry(3), entry(1), entry(2)))
        fixture.previews.close()
        assertEquals(200, fixture.post(token, "/api/collections/apply", JSONObject()
            .put("collectionId", collectionId).put("queueVersion", 0)).code)
        assertEquals(listOf("video000001", "video000002"), fixture.videoIds())
        val listed = JSONObject(fixture.get(token, "/api/collections").body).getJSONArray("collections")
        assertEquals(1, listed.length())
        assertEquals(listOf("video000001", "video000002"),
            List(listed.getJSONObject(0).getJSONArray("videoIds").length()) { listed.getJSONObject(0).getJSONArray("videoIds").getString(it) })
        assertEquals(200, fixture.post(token, "/api/collections/delete", JSONObject().put("collectionId", collectionId)).code)
        assertEquals(0, JSONObject(fixture.get(token, "/api/collections").body).getJSONArray("collections").length())
        assertEquals(0, fixture.playerCalls)
        fixture.close()
    }

    @Test
    fun savedProgramCreationReplaySurvivesRestartAndDetectsChangedRequest() = runTest {
        val fixture = Fixture(this)
        val token = fixture.pair()
        val preview = fixture.preview(token)
        runCurrent()
        fun body(name: String) = JSONObject().put("previewId", preview)
            .put("indices", JSONArray(listOf(2, 1))).put("name", name)
        val first = fixture.post(token, "/api/collections/save", body("Вечер"), "save-restart")
        assertEquals(200, first.code)
        fixture.close()
        File(fixture.directory, "responses.json").delete()
        val restarted = Fixture(this, fixture.directory)
        val replay = restarted.post(token, "/api/collections/save", body("Вечер"), "save-restart")
        assertEquals(200, replay.code)
        assertEquals(first.body, replay.body)
        assertEquals(409, restarted.post(token, "/api/collections/save", body("Другое"), "save-restart").code)
        assertEquals(1, JSONObject(restarted.get(token, "/api/collections").body).getJSONArray("collections").length())
        restarted.close()
    }

    @Test
    fun playlistRoutesKeepAuthorizationAndOriginChecks() = runTest {
        val fixture = Fixture(this)
        val token = fixture.pair()
        val body = JSONObject().put("url", PLAYLIST_URL).put("idempotencyKey", "security-check").toString()
        assertEquals(401, fixture.controller.handle(ApiRequest("POST", "/api/playlists/preview", fixture.headers(null), body, "client")).code)
        assertEquals(403, fixture.controller.handle(ApiRequest("POST", "/api/playlists/preview", fixture.headers(token) + ("origin" to "https://evil.example"), body, "client")).code)
        assertEquals(400, fixture.post(token, "/api/playlists/preview", JSONObject().put("url", "http://127.0.0.1/private")).code)
        assertTrue(fixture.videoIds().isEmpty())
        fixture.close()
    }

    @Test fun playlistIdentityAndNamesSurviveRestartAndAllDuplicateImport() = runTest {
        val f=Fixture(this);val token=f.pair();val id=f.preview(token);runCurrent()
        val first=f.apply(token,id,listOf(2,1));assertEquals(200,first.code)
        val state=f.coordinator.currentSnapshot().queueState
        assertEquals("Тест",state.groups.single().name);assertEquals(listOf("Видео 1","Видео 2"),state.items.map {it.title})
        val repeated=f.apply(token,id,listOf(1,2),version=1);assertEquals(200,repeated.code)
        assertEquals(1L,f.coordinator.currentSnapshot().queueState.version);assertEquals(1,f.coordinator.currentSnapshot().queueState.groups.size)
        assertEquals(0,JSONObject(repeated.body).getJSONObject("addition").getInt("added"))
        f.close();val restored=Fixture(this,f.directory)
        assertEquals(state.groups,restored.coordinator.currentSnapshot().queueState.groups)
        assertEquals(state.items,restored.coordinator.currentSnapshot().queueState.items);restored.close()
    }

    private inner class Fixture(scope: TestScope, val directory: File = temporaryFolder.newFolder()) {
        var result = PlaylistResult("Тест", listOf(entry(1), entry(2)), PlaylistStatus.COMPLETE, null, 2)
        var playerCalls = 0
        private val tokens = AtomicTokenStore(File(directory, "tokens.json"))
        private val settings = object : PinSettings {
            val record = PinSecurity.hashPin("739251")
            override fun hasPin() = true
            override fun pinRecord() = record
            override fun savePinRecord(record: String) = Unit
        }
        private val pairing = PairingManager(settings, tokens) { 1_000 }
        val coordinator = PlaybackCoordinator(
            object : YouTubeSource { override suspend fun resolve(videoId: String): ResolvedVideo = error("Batch must not extract videos") },
            AtomicQueueStore(File(directory, "queue.json")),
            object : PlaybackPlayer {
                override fun setListener(listener: PlaybackPlayer.Listener) = Unit
                override fun prepare(generation: Long, video: ResolvedVideo, positionMs: Long, playWhenReady: Boolean) { playerCalls++ }
                override fun play() { playerCalls++ }
                override fun pause() { playerCalls++ }
                override fun stop() { playerCalls++ }
                override fun currentPositionMs() = 0L
                override fun release() = Unit
            }, scope,
        )
        val previews = PlaylistPreviewManager(PlaylistSource { result }, scope)
        private val dispatcher = object : CallDispatcher {
            @Synchronized override fun <T> call(block: () -> T): T = block()
        }
        lateinit var controller: ApiController
        private var sequence = 0

        init {
            val playlists = PlaylistApi(previews, AtomicCollectionStore(File(directory, "collections.json")), coordinator, dispatcher) { controller.stateBody() }
            controller = ApiController(pairing, tokens, AtomicIdempotencyStore(File(directory, "responses.json")),
                coordinator, dispatcher, RequestSecurity(setOf("127.0.0.1"), 8765), { "test" }, playlists)
        }

        fun headers(token: String?) = buildMap {
            put("host", "127.0.0.1:8765")
            put("origin", "http://127.0.0.1:8765")
            put("x-parentcast-client", "m1")
            if (token != null) put("authorization", "Bearer $token")
        }

        fun pair(): String {
            return pairing.completePairing("739251", "Test", "client")!!.second
        }

        fun get(token: String, path: String) = controller.handle(ApiRequest("GET", path, headers(token), "", "client"))
        fun post(token: String, path: String, body: JSONObject, key: String = "command-${++sequence}") =
            controller.handle(ApiRequest("POST", path, headers(token), body.put("idempotencyKey", key).toString(), "client"))
        fun preview(token: String): String {
            val response = post(token, "/api/playlists/preview", JSONObject().put("url", PLAYLIST_URL))
            assertEquals(202, response.code)
            return JSONObject(response.body).getString("previewId")
        }
        fun apply(token: String, id: String, indices: List<Int>, version: Long = 0, key: String = "apply-command-${++sequence}") =
            post(token, "/api/playlists/apply", JSONObject().put("previewId", id).put("indices", JSONArray(indices)).put("queueVersion", version), key)
        fun videoIds() = coordinator.currentSnapshot().queueState.items.map { it.videoId }
        fun close() { coordinator.close(); previews.close() }
    }

    companion object {
        private const val PLAYLIST_URL = "https://www.youtube.com/playlist?list=PLabcdefghij012345"
        private fun entry(index: Int, selectable: Boolean = true) = PlaylistEntry(index,
            "video" + index.toString().padStart(6, '0'), "Видео $index",
            if (selectable) PlaylistAvailability.AVAILABLE else PlaylistAvailability.UNAVAILABLE,
            selectable, 10L, "Канал")
    }
}

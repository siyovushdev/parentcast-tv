package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.collections.AtomicCollectionStore
import dev.siyovush.parentcast.tv.playback.PlaybackCoordinator
import dev.siyovush.parentcast.tv.playback.PlaybackPlayer
import dev.siyovush.parentcast.tv.playlist.*
import dev.siyovush.parentcast.tv.queue.AtomicIdempotencyStore
import dev.siyovush.parentcast.tv.queue.AtomicQueueStore
import dev.siyovush.parentcast.tv.youtube.ResolvedVideo
import dev.siyovush.parentcast.tv.youtube.YouTubeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/** Real loopback HTTP/NanoHTTPD body decoding. YouTube itself is a deterministic fixture. */
class ParentHttpServerM2Test {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun utf8JsonWithoutCharsetAndWithCharsetPreservesRussianCollectionNames() {
        Fixture().use { fixture ->
            listOf("application/json", "application/json; charset=UTF-8").forEachIndexed { index, contentType ->
                val (previewCode, preview) = fixture.http("POST", "/api/playlists/preview", JSONObject()
                    .put("url", "https://www.youtube.com/playlist?list=PLabcdefghij012345")
                    .put("idempotencyKey", "preview-http-$index"), contentType)
                assertEquals(202, previewCode)
                val id = preview.getString("previewId")
                assertEquals("COMPLETE", fixture.http("GET", "/api/playlists/preview/$id").second.getString("status"))
                val name = "Программа №${index + 1} — Сказки 🌙"
                val body = JSONObject().put("previewId", id).put("indices", JSONArray(listOf(1)))
                    .put("name", name).put("idempotencyKey", "save-http-$index")
                val saved = fixture.http("POST", "/api/collections/save", body, contentType)
                assertEquals(200, saved.first)
                assertEquals(name, saved.second.getJSONObject("collection").getString("name"))
                val listed = fixture.http("GET", "/api/collections")
                assertEquals(200, listed.first)
                assertEquals(name, listed.second.getJSONArray("collections").getJSONObject(index).getString("name"))
                assertEquals(403, fixture.http("POST", "/api/collections/save", body, contentType, "m2").first)
            }
        }
    }

    private inner class Fixture : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // Ask the OS for an ephemeral port; no fixed test port or external network is used.
        private val port = ServerSocket(0).use { it.localPort }
        private val tokens = AtomicTokenStore(File(temporaryFolder.root, "tokens.json"))
        private val token = tokens.create("Loopback test").second
        private val previews = PlaylistPreviewManager(PlaylistSource {
            PlaylistResult("Предпросмотр", listOf(PlaylistEntry(1, "video000001", "Сказка",
                PlaylistAvailability.AVAILABLE, true, 10, "Канал")), PlaylistStatus.COMPLETE, null, 1)
        }, scope)
        private val coordinator = PlaybackCoordinator(
            object : YouTubeSource { override suspend fun resolve(videoId: String): ResolvedVideo = error("No playback expected") },
            AtomicQueueStore(File(temporaryFolder.root, "queue.json")), object : PlaybackPlayer {
                override fun setListener(listener: PlaybackPlayer.Listener) = Unit
                override fun prepare(generation: Long, video: ResolvedVideo, positionMs: Long, playWhenReady: Boolean) = Unit
                override fun play() = Unit
                override fun pause() = Unit
                override fun stop() = Unit
                override fun currentPositionMs() = 0L
                override fun release() = Unit
            }, scope,
        )
        private val dispatcher = object : CallDispatcher { override fun <T> call(block: () -> T): T = block() }
        private val playlists = PlaylistApi(previews, AtomicCollectionStore(File(temporaryFolder.root, "collections.json")), coordinator, dispatcher) { "{}" }
        private val controller = ApiController(PairingManager(object : PinSettings {
            override fun hasPin() = false
            override fun pinRecord(): String? = null
            override fun savePinRecord(record: String) = Unit
        }, tokens), tokens, AtomicIdempotencyStore(File(temporaryFolder.root, "responses.json")),
            coordinator, dispatcher, RequestSecurity(setOf("127.0.0.1"), port), { "test" }, playlists)
        private val server = ParentHttpServer(port, controller).apply { start(5_000, true) }

        fun http(method: String, path: String, body: JSONObject? = null, contentType: String = "application/json", client: String = "m1"): Pair<Int, JSONObject> {
            val bytes = body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val response = Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                val headers = "$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nOrigin: http://127.0.0.1:$port\r\n" +
                    "Authorization: Bearer $token\r\nX-ParentCast-Client: $client\r\nContent-Type: $contentType\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
            return response.substringBefore("\r\n").split(' ')[1].toInt() to JSONObject(response.substringAfter("\r\n\r\n"))
        }

        override fun close() {
            server.stop()
            previews.close()
            coordinator.close()
            scope.cancel()
        }
    }
}

package dev.siyovush.parentcast.tv.playback

import dev.siyovush.parentcast.tv.queue.QueueItem
import dev.siyovush.parentcast.tv.queue.QueueState
import dev.siyovush.parentcast.tv.queue.QueueStore
import dev.siyovush.parentcast.tv.queue.PlaybackIntent
import dev.siyovush.parentcast.tv.youtube.ResolvedVideo
import dev.siyovush.parentcast.tv.youtube.StreamSpec
import dev.siyovush.parentcast.tv.youtube.YouTubeSource
import dev.siyovush.parentcast.tv.youtube.YouTubeSourceException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {
    @Test
    fun restoredPauseIntentPreparesPositionWithoutAutoplay() = runTest {
        val initial = QueueState.Empty
            .add(QueueItem("q1", "video000001"))
            .withPosition(4_000)
            .play()
            .togglePause()
        val source = FakeSource()
        val player = FakePlayer()
        val store = MemoryStore(initial)
        val coordinator = coordinator(source, store, player)
        coordinator.start()
        advanceUntilIdle()

        assertEquals(1, player.prepareCalls.size)
        assertEquals(4_000, player.prepareCalls.single().positionMs)
        assertFalse(player.prepareCalls.single().playWhenReady)
        assertEquals(PlaybackStatus.PAUSED, player.status(coordinator))
        coordinator.close()
    }

    @Test
    fun naturalEndAdvancesThreeItemsAndStopsAfterLast() = runTest {
        val source = FakeSource()
        val player = FakePlayer()
        val coordinator = coordinator(source, MemoryStore(), player)
        var snapshot = CoordinatorSnapshot()
        coordinator.setListener { snapshot = it }
        coordinator.start()
        coordinator.add("video000001")
        coordinator.add("video000002")
        coordinator.add("video000003")
        coordinator.play()
        advanceUntilIdle()

        assertEquals("video000001", player.prepareCalls[0].videoId)
        coordinator.onEnded(snapshot.generation)
        advanceUntilIdle()
        assertEquals("video000002", player.prepareCalls[1].videoId)
        coordinator.onEnded(snapshot.generation)
        advanceUntilIdle()
        assertEquals("video000003", player.prepareCalls[2].videoId)
        coordinator.onEnded(snapshot.generation)

        assertEquals(PlaybackStatus.ENDED, snapshot.status)
        assertEquals(PlaybackIntent.STOP, snapshot.queueState.intent)
        assertTrue(snapshot.queueState.finished)
        assertEquals(3, player.prepareCalls.size)
        coordinator.close()
    }

    @Test
    fun lateExtractorResultAfterStopIsIgnored() = runTest {
        val source = FakeSource().apply {
            handler = { CompletableDeferred<ResolvedVideo>().await() }
        }
        val player = FakePlayer()
        val store = MemoryStore()
        val coordinator = coordinator(source, store, player)
        var snapshot = CoordinatorSnapshot()
        coordinator.setListener { snapshot = it }
        coordinator.start()
        coordinator.add("video000001")
        coordinator.play()
        runCurrent()
        val staleGeneration = snapshot.generation
        val queueItemId = snapshot.queueState.currentItem!!.queueItemId

        coordinator.stop()
        coordinator.handleResolved(staleGeneration, queueItemId, resolved("video000001"))

        assertTrue(player.prepareCalls.isEmpty())
        assertEquals(PlaybackIntent.STOP, store.state.intent)
        assertEquals(PlaybackStatus.STOPPED, snapshot.status)
        coordinator.close()
    }

    @Test
    fun lateExtractorResultAfterNextCannotReplaceNewItem() = runTest {
        val responses = mutableMapOf<String, CompletableDeferred<ResolvedVideo>>()
        val source = FakeSource().apply {
            handler = { videoId -> responses.getOrPut(videoId) { CompletableDeferred() }.await() }
        }
        val player = FakePlayer()
        val coordinator = coordinator(source, MemoryStore(), player)
        var snapshot = CoordinatorSnapshot()
        coordinator.setListener { snapshot = it }
        coordinator.start()
        coordinator.add("video000001")
        coordinator.add("video000002")
        coordinator.play()
        runCurrent()
        val firstGeneration = snapshot.generation
        val firstQueueItemId = snapshot.queueState.items[0].queueItemId

        coordinator.next()
        runCurrent()
        coordinator.handleResolved(firstGeneration, firstQueueItemId, resolved("video000001"))
        assertTrue(player.prepareCalls.isEmpty())
        responses.getValue("video000002").complete(resolved("video000002"))
        advanceUntilIdle()

        assertEquals(listOf("video000002"), player.prepareCalls.map { it.videoId })
        assertEquals("video000002", snapshot.queueState.currentItem?.videoId)
        coordinator.close()
    }

    @Test
    fun unavailableVideoProducesControlledErrorWithoutExternalFallback() = runTest {
        val source = FakeSource().apply {
            handler = {
                throw YouTubeSourceException(PlaybackFailure(PlaybackErrorKind.UNAVAILABLE))
            }
        }
        val player = FakePlayer()
        val coordinator = coordinator(source, MemoryStore(), player)
        var snapshot = CoordinatorSnapshot()
        coordinator.setListener { snapshot = it }
        coordinator.start()
        coordinator.add("video000001")
        coordinator.play()
        advanceUntilIdle()

        assertEquals(PlaybackStatus.ERROR, snapshot.status)
        assertEquals(PlaybackErrorKind.UNAVAILABLE, snapshot.error?.kind)
        assertTrue(player.prepareCalls.isEmpty())
        assertEquals("video000001", snapshot.queueState.currentItem?.videoId)
        coordinator.close()
    }

    @Test
    fun forbiddenMediaUrlIsRefreshedOnlyOnce() = runTest {
        val source = FakeSource()
        val player = FakePlayer()
        val coordinator = coordinator(source, MemoryStore(), player)
        var snapshot = CoordinatorSnapshot()
        coordinator.setListener { snapshot = it }
        coordinator.start()
        coordinator.add("video000001")
        coordinator.play()
        advanceUntilIdle()

        coordinator.onError(
            snapshot.generation,
            PlaybackFailure(PlaybackErrorKind.NETWORK, canRefresh = true),
        )
        advanceUntilIdle()
        assertEquals(2, player.prepareCalls.size)

        coordinator.onError(
            snapshot.generation,
            PlaybackFailure(PlaybackErrorKind.NETWORK, canRefresh = true),
        )
        assertEquals(PlaybackStatus.ERROR, snapshot.status)
        assertEquals(2, player.prepareCalls.size)
        coordinator.close()
    }

    @Test
    fun pausingSavesActualPositionAndIntent() = runTest {
        val source = FakeSource()
        val player = FakePlayer()
        val store = MemoryStore()
        val coordinator = coordinator(source, store, player)
        coordinator.start()
        coordinator.add("video000001")
        coordinator.play()
        advanceUntilIdle()
        player.positionMs = 17_000

        coordinator.pauseResume()

        assertEquals(17_000, store.state.positionMs)
        assertEquals(PlaybackIntent.PAUSE, store.state.intent)
        assertEquals(1, player.pauseCount)
        coordinator.close()
    }

    @Test
    fun restoredStopIntentDoesNotExtractOrAutoplay() = runTest {
        val initial = QueueState.Empty
            .add(QueueItem("q1", "video000001"))
            .withPosition(7_000)
        val player = FakePlayer()
        val store = MemoryStore(initial)
        val coordinator = coordinator(FakeSource(), store, player)

        coordinator.start()
        advanceUntilIdle()

        assertTrue(player.prepareCalls.isEmpty())
        assertEquals(PlaybackIntent.STOP, store.state.intent)
        assertEquals(7_000, store.state.positionMs)
        coordinator.close()
    }

    @Test
    fun restoredPlayIntentResumesSameItemAtSavedPosition() = runTest {
        val initial = QueueState.Empty
            .add(QueueItem("q1", "video000001"))
            .play()
            .withPosition(8_000)
        val player = FakePlayer()
        val coordinator = coordinator(FakeSource(), MemoryStore(initial), player)

        coordinator.start()
        advanceUntilIdle()

        assertEquals(1, player.prepareCalls.size)
        assertEquals("video000001", player.prepareCalls.single().videoId)
        assertEquals(8_000, player.prepareCalls.single().positionMs)
        assertTrue(player.prepareCalls.single().playWhenReady)
        coordinator.close()
    }

    private fun TestScope.coordinator(
        source: FakeSource,
        store: MemoryStore,
        player: FakePlayer,
    ) = PlaybackCoordinator(
        source = source,
        store = store,
        player = player,
        scope = this,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun resolved(videoId: String) = ResolvedVideo(
        videoId = videoId,
        title = "Test $videoId",
        durationSeconds = 10,
        video = StreamSpec("https://media.invalid/$videoId/video.mp4", "video/mp4", emptyMap()),
        audio = null,
        selectionDescription = "360p, H.264/AAC, test",
    )
}

private class FakeSource : YouTubeSource {
    var handler: suspend (String) -> ResolvedVideo = { videoId ->
        ResolvedVideo(
            videoId = videoId,
            title = "Test $videoId",
            durationSeconds = 10,
            video = StreamSpec("https://media.invalid/$videoId/video.mp4", "video/mp4", emptyMap()),
            audio = null,
            selectionDescription = "360p, H.264/AAC, test",
        )
    }

    override suspend fun resolve(videoId: String): ResolvedVideo = handler(videoId)
}

private class MemoryStore(initial: QueueState = QueueState.Empty) : QueueStore {
    var state = initial
    override fun read(): QueueState = state
    override fun write(state: QueueState) {
        this.state = state
    }
}

private data class PrepareCall(
    val videoId: String,
    val positionMs: Long,
    val playWhenReady: Boolean,
)

private class FakePlayer : PlaybackPlayer {
    val prepareCalls = mutableListOf<PrepareCall>()
    var positionMs = 0L
    var pauseCount = 0

    override fun setListener(listener: PlaybackPlayer.Listener) = Unit

    override fun prepare(
        generation: Long,
        video: ResolvedVideo,
        positionMs: Long,
        playWhenReady: Boolean,
    ) {
        prepareCalls += PrepareCall(video.videoId, positionMs, playWhenReady)
    }

    override fun play() = Unit

    override fun pause() {
        pauseCount++
    }

    override fun stop() = Unit
    override fun currentPositionMs(): Long = positionMs
    override fun release() = Unit

    fun status(coordinator: PlaybackCoordinator): PlaybackStatus {
        var status = PlaybackStatus.IDLE
        coordinator.setListener { status = it.status }
        return status
    }
}

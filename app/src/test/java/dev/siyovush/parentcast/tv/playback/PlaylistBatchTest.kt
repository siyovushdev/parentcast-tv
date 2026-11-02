package dev.siyovush.parentcast.tv.playback

import dev.siyovush.parentcast.tv.queue.*
import dev.siyovush.parentcast.tv.youtube.ResolvedVideo
import dev.siyovush.parentcast.tv.youtube.StreamSpec
import dev.siyovush.parentcast.tv.youtube.YouTubeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistBatchTest {
    @Test
    fun batchWritesOnceWithReceiptAndDoesNotStartStoppedPlayer() = runTest {
        val store = BatchStore()
        val player = BatchPlayer()
        val coordinator = coordinator(store, player)
        coordinator.start()
        assertEquals(BatchAddResult.APPLIED, coordinator.addBatch(listOf("video000002", "video000001", "video000002"), 0, "command-1"))
        assertEquals(listOf("video000002", "video000001", "video000002"), store.state.items.map { it.videoId })
        assertEquals(1, store.writes)
        assertEquals(1L, store.state.version)
        assertEquals(1, store.state.batchReceipts.size)
        assertEquals(PlaybackIntent.STOP, store.state.intent)
        assertEquals(0, player.calls)
        coordinator.close()
    }

    @Test
    fun batchDoesNotInterruptCurrentPlaybackOrChangeItsGenerationPositionAndIntent() = runTest {
        val store = BatchStore(QueueState.Empty.add(QueueItem("first", "video000001")).play().withPosition(1234))
        val player = BatchPlayer()
        val coordinator = coordinator(store, player)
        coordinator.start()
        runCurrent()
        coordinator.onFirstFrame(coordinator.currentSnapshot().generation)
        val before = coordinator.currentSnapshot()
        val calls = player.calls

        assertEquals(BatchAddResult.APPLIED, coordinator.addBatch(listOf("video000002"), before.queueState.version, "command-2"))
        val after = coordinator.currentSnapshot()
        assertEquals(calls, player.calls)
        assertEquals(before.generation, after.generation)
        assertEquals(before.queueState.currentItem, after.queueState.currentItem)
        assertEquals(before.queueState.positionMs, after.queueState.positionMs)
        assertEquals(PlaybackIntent.PLAY, after.queueState.intent)
        assertEquals(PlaybackStatus.PLAYING, after.status)
        coordinator.close()
    }

    @Test
    fun failedAtomicWriteLeavesQueueAndReceiptsUnchangedAndCanBeRetried() = runTest {
        val store = BatchStore().apply { fail = true }
        val coordinator = coordinator(store, BatchPlayer())
        assertEquals(BatchAddResult.STORAGE_ERROR, coordinator.addBatch(listOf("video000001"), 0, "command-3"))
        assertEquals(QueueState.Empty, coordinator.currentSnapshot().queueState)
        assertEquals(QueueState.Empty, store.state)
        assertNull(coordinator.batchReplay("command-3", "fingerprint"))
        store.fail = false
        assertEquals(BatchAddResult.APPLIED, coordinator.addBatch(listOf("video000001"), 0, "command-3"))
        assertEquals(1, store.state.items.size)
        coordinator.close()
    }

    @Test
    fun persistedReceiptPreventsDuplicateEvenWithStaleVersionAfterRestart() = runTest {
        val store = BatchStore()
        val first = coordinator(store, BatchPlayer())
        assertEquals(BatchAddResult.APPLIED, first.addBatch(listOf("video000001"), 0, "command-4", "fingerprint"))
        val diskSnapshot = QueueSnapshotCodec.decode(QueueSnapshotCodec.encode(store.state))
        first.close()
        val restored = coordinator(BatchStore(diskSnapshot), BatchPlayer())
        assertEquals(BatchAddResult.REPLAY, restored.batchReplay("command-4", "fingerprint"))
        assertEquals(BatchAddResult.REPLAY, restored.addBatch(listOf("video000001"), 0, "command-4", "fingerprint"))
        assertEquals(BatchAddResult.KEY_REUSED, restored.addBatch(listOf("video000002"), 1, "command-4", "other"))
        assertEquals(listOf("video000001"), restored.currentSnapshot().queueState.items.map { it.videoId })
        restored.close()
    }

    @Test
    fun competingVersionAndInvalidBatchNeverAppendAnyItems() = runTest {
        val store = BatchStore()
        val coordinator = coordinator(store, BatchPlayer())
        assertEquals(BatchAddResult.APPLIED, coordinator.addBatch(listOf("video000001"), 0, "command-5"))
        assertEquals(BatchAddResult.CONFLICT, coordinator.addBatch(listOf("video000002", "video000003"), 0, "command-6"))
        assertThrows(IllegalArgumentException::class.java) { coordinator.addBatch(listOf("video000002", "invalid"), 1, "command-7") }
        assertThrows(IllegalArgumentException::class.java) { coordinator.addBatch(List(501) { "video000001" }, 1, "command-8") }
        assertEquals(listOf("video000001"), store.state.items.map { it.videoId })
        assertEquals(1, store.writes)
        coordinator.close()
    }

    private fun TestScope.coordinator(store: BatchStore, player: BatchPlayer) = PlaybackCoordinator(
        object : YouTubeSource {
            override suspend fun resolve(videoId: String) = ResolvedVideo(videoId, "Test", 10,
                StreamSpec("https://media.invalid/video.mp4", "video/mp4", emptyMap()), null, "test")
        }, store, player, this, StandardTestDispatcher(testScheduler),
    )

    private class BatchStore(var state: QueueState = QueueState.Empty) : QueueStore {
        var writes = 0
        var fail = false
        override fun read() = state
        override fun write(state: QueueState) {
            if (fail) throw IOException("Simulated write failure")
            writes++
            this.state = state
        }
    }

    private class BatchPlayer : PlaybackPlayer {
        var calls = 0
        override fun setListener(listener: PlaybackPlayer.Listener) = Unit
        override fun prepare(generation: Long, video: ResolvedVideo, positionMs: Long, playWhenReady: Boolean) { calls++ }
        override fun play() { calls++ }
        override fun pause() { calls++ }
        override fun stop() { calls++ }
        override fun currentPositionMs() = 0L
        override fun release() = Unit
    }
}

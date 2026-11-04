package dev.siyovush.parentcast.tv.playback

import dev.siyovush.parentcast.tv.queue.BatchReceipt
import dev.siyovush.parentcast.tv.queue.PlaybackIntent
import dev.siyovush.parentcast.tv.queue.QueueItem
import dev.siyovush.parentcast.tv.queue.QueueState
import dev.siyovush.parentcast.tv.queue.QueueStore
import dev.siyovush.parentcast.tv.youtube.ResolvedVideo
import dev.siyovush.parentcast.tv.youtube.StreamSpec
import dev.siyovush.parentcast.tv.youtube.YouTubeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Coordinator restoration tests use fixtures only; they do not claim an actual Android reboot. */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupPlaybackPolicyTest {
    @Test
    fun diskRestoreRetainsPlaylistAndPositionAfterPauseAndInterruptedPlay() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("parentcast-resume").toFile()
        try {
            listOf(PlaybackIntent.PAUSE, PlaybackIntent.PLAY).forEach { intent ->
                val base=queue(intent)
                val group=dev.siyovush.parentcast.tv.queue.QueueGroup("g_"+"a".repeat(32),"Fixture",listOf("video000002","video000001"),dev.siyovush.parentcast.tv.queue.QueueGroupSource.COLLECTION)
                val original=base.copy(groups=listOf(group),activeGroupId=group.id)
                val file=java.io.File(dir,"queue.json")
                dev.siyovush.parentcast.tv.queue.AtomicQueueStore(file).write(original)
                val player=StartupPlayer()
                val restored=PlaybackCoordinator(StartupSource(),dev.siyovush.parentcast.tv.queue.AtomicQueueStore(file),player,this,StandardTestDispatcher(testScheduler),autoResumeOnStartup=false)
                restored.start();advanceUntilIdle()
                assertEquals(42_000,restored.currentSnapshot().queueState.positionMs)
                assertEquals(group.id,restored.currentSnapshot().queueState.activeGroupId)
                assertEquals(listOf(StartupPreparation("video000002",42_000,false)),player.prepared)
                restored.play()
                assertEquals(1,player.playCalls)
                assertEquals(42_000,restored.currentSnapshot().queueState.positionMs)
                restored.close()
            }
        } finally {dir.deleteRecursively()}
    }

    @Test
    fun liveOwnerResumesOnlyUnfinishedPlayWithoutStorageWarnings() {
        val playing = CoordinatorSnapshot(queueState = queue(PlaybackIntent.PLAY))
        assertTrue(StartupPlaybackPolicy.resumeLiveOwner(playing))
        assertFalse(StartupPlaybackPolicy.resumeLiveOwner(playing.copy(storageWarning = true)))
        assertFalse(StartupPlaybackPolicy.resumeLiveOwner(playing.copy(queueState = queue(PlaybackIntent.PAUSE))))
        assertFalse(StartupPlaybackPolicy.resumeLiveOwner(playing.copy(queueState = queue(PlaybackIntent.STOP))))
        assertFalse(StartupPlaybackPolicy.resumeLiveOwner(playing.copy(queueState = playing.queueState.copy(finished = true))))
        assertFalse(StartupPlaybackPolicy.resumeLiveOwner(CoordinatorSnapshot()))
    }

    @Test
    fun handoffAfterFailedPauseCommitDoesNotAutoplayStaleDiskPlayEvenWhenPreferenceIsEnabled() = runTest {
        val previous = fixture(queue(PlaybackIntent.PLAY), autoResume = false)
        previous.store.failWrites = true
        previous.coordinator.start()
        advanceUntilIdle()
        val liveOwner: CoordinatorSnapshot? = previous.coordinator.currentSnapshot()
        assertEquals(PlaybackIntent.PAUSE, liveOwner?.queueState?.intent)
        assertEquals(true, liveOwner?.storageWarning)
        assertEquals(PlaybackIntent.PLAY, previous.store.state.intent)
        previous.coordinator.close()

        val preference = true
        val handoffAutoResume = liveOwner?.let(StartupPlaybackPolicy::resumeLiveOwner) ?: preference
        assertFalse(handoffAutoResume)
        previous.store.failWrites = false
        val source = StartupSource()
        val player = StartupPlayer()
        val replacement = PlaybackCoordinator(source, previous.store, player, this,
            StandardTestDispatcher(testScheduler), autoResumeOnStartup = handoffAutoResume)

        replacement.start()
        advanceUntilIdle()

        assertEquals(PlaybackIntent.PAUSE, previous.store.state.intent)
        assertEquals(PlaybackStatus.PAUSED, replacement.currentSnapshot().status)
        assertEquals(listOf(StartupPreparation("video000002", 42_000, false)), player.prepared)
        assertEquals(0, player.playCalls)
        replacement.close()
    }

    @Test
    fun enabledAutoResumePreparesExactlyThePersistedVideoAndPosition() = runTest {
        val initial = queue(PlaybackIntent.PLAY)
        val fixture = fixture(initial, autoResume = true)

        fixture.coordinator.start()
        advanceUntilIdle()

        assertEquals(listOf("video000002"), fixture.source.requested)
        assertEquals(listOf(StartupPreparation("video000002", 42_000, true)), fixture.player.prepared)
        assertEquals(initial, fixture.coordinator.currentSnapshot().queueState)
        assertEquals(0, fixture.store.writes)
        fixture.coordinator.close()
    }

    @Test
    fun disabledAutoResumeCommitsPauseBeforeResolvingAndKeepsQueueIdentity() = runTest {
        val initial = queue(PlaybackIntent.PLAY)
        val expected = initial.copy(intent = PlaybackIntent.PAUSE, version = initial.version + 1)
        val fixture = fixture(initial, autoResume = false)
        fixture.source.onResolve = {
            assertEquals(expected, fixture.store.state)
            assertEquals(1, fixture.store.writes)
        }

        fixture.coordinator.start()
        assertEquals(expected, fixture.store.state)
        assertTrue(fixture.source.requested.isEmpty())
        advanceUntilIdle()

        assertEquals(listOf(StartupPreparation("video000002", 42_000, false)), fixture.player.prepared)
        assertEquals(expected, fixture.coordinator.currentSnapshot().queueState)
        assertEquals(PlaybackStatus.PAUSED, fixture.coordinator.currentSnapshot().status)
        assertEquals(0, fixture.player.playCalls)
        fixture.coordinator.close()
    }

    @Test
    fun savedPauseNeverAutoplaysWithEitherAutoResumeSetting() = runTest {
        listOf(false, true).forEach { autoResume ->
            val initial = queue(PlaybackIntent.PAUSE)
            val fixture = fixture(initial, autoResume)
            fixture.coordinator.start()
            advanceUntilIdle()

            assertEquals(listOf(StartupPreparation("video000002", 42_000, false)), fixture.player.prepared)
            assertEquals(initial, fixture.coordinator.currentSnapshot().queueState)
            assertEquals(PlaybackStatus.PAUSED, fixture.coordinator.currentSnapshot().status)
            assertEquals(0, fixture.store.writes)
            assertEquals(0, fixture.player.playCalls)
            fixture.coordinator.close()
        }
    }

    @Test
    fun savedStopDoesNotResolveOrPrepareWithEitherAutoResumeSetting() = runTest {
        listOf(false, true).forEach { autoResume ->
            val initial = queue(PlaybackIntent.STOP)
            val fixture = fixture(initial, autoResume)
            fixture.coordinator.start()
            advanceUntilIdle()

            assertTrue(fixture.source.requested.isEmpty())
            assertTrue(fixture.player.prepared.isEmpty())
            assertEquals(initial, fixture.coordinator.currentSnapshot().queueState)
            assertEquals(PlaybackStatus.STOPPED, fixture.coordinator.currentSnapshot().status)
            assertEquals(0, fixture.store.writes)
            fixture.coordinator.close()
        }
    }

    @Test
    fun failedPauseCommitBlocksAutomaticResolutionIncludingLaterSessionCallback() = runTest {
        val initial = queue(PlaybackIntent.PLAY)
        val fixture = fixture(initial, autoResume = false)
        fixture.store.failWrites = true

        fixture.coordinator.start()
        fixture.coordinator.onSessionChanged()
        advanceUntilIdle()

        assertEquals(initial, fixture.store.state)
        assertTrue(fixture.source.requested.isEmpty())
        assertTrue(fixture.player.prepared.isEmpty())
        assertEquals(PlaybackIntent.PAUSE, fixture.coordinator.currentSnapshot().queueState.intent)
        assertEquals(42_000, fixture.coordinator.currentSnapshot().queueState.positionMs)
        assertEquals(PlaybackStatus.PAUSED, fixture.coordinator.currentSnapshot().status)
        assertTrue(fixture.coordinator.currentSnapshot().storageWarning)
        fixture.coordinator.close()
    }

    @Test
    fun explicitPlayAfterDisabledAutoResumeCanResumeTheSamePosition() = runTest {
        val fixture = fixture(queue(PlaybackIntent.PLAY), autoResume = false)
        fixture.coordinator.start()
        advanceUntilIdle()

        fixture.coordinator.play()

        assertEquals(1, fixture.player.playCalls)
        assertEquals(PlaybackIntent.PLAY, fixture.store.state.intent)
        assertEquals("video000002", fixture.store.state.currentItem!!.videoId)
        assertEquals(42_000, fixture.store.state.positionMs)
        assertEquals(1, fixture.source.requested.size)
        fixture.coordinator.close()
    }

    @Test
    fun disabledAutoResumeWaitsForSessionWithoutLosingTheCommittedPause() = runTest {
        var authorized = false
        val fixture = fixture(queue(PlaybackIntent.PLAY), autoResume = false, authorizationGate = { authorized })
        fixture.coordinator.start()
        advanceUntilIdle()
        assertTrue(fixture.source.requested.isEmpty())
        assertEquals(PlaybackIntent.PAUSE, fixture.store.state.intent)

        authorized = true
        fixture.coordinator.onSessionChanged()
        advanceUntilIdle()

        assertEquals(listOf(StartupPreparation("video000002", 42_000, false)), fixture.player.prepared)
        assertEquals(PlaybackStatus.PAUSED, fixture.coordinator.currentSnapshot().status)
        fixture.coordinator.close()
    }

    @Test
    fun finishedAndEmptyQueuesRemainUnchangedAndNeverStart() = runTest {
        listOf(QueueState.Empty, queue(PlaybackIntent.PLAY).copy(finished = true)).forEach { initial ->
            val fixture = fixture(initial, autoResume = false)
            fixture.coordinator.start()
            advanceUntilIdle()
            assertEquals(initial, fixture.coordinator.currentSnapshot().queueState)
            assertTrue(fixture.source.requested.isEmpty())
            assertTrue(fixture.player.prepared.isEmpty())
            assertEquals(0, fixture.store.writes)
            fixture.coordinator.close()
        }
    }

    private fun TestScope.fixture(
        initial: QueueState,
        autoResume: Boolean,
        authorizationGate: () -> Boolean = { true },
    ): StartupFixture {
        val source = StartupSource()
        val store = StartupStore(initial)
        val player = StartupPlayer()
        return StartupFixture(source, store, player, PlaybackCoordinator(source, store, player, this,
            StandardTestDispatcher(testScheduler), authorizationGate, autoResume))
    }

    private fun queue(intent: PlaybackIntent) = QueueState(
        items = listOf(QueueItem("q1", "video000001"), QueueItem("q2", "video000002"), QueueItem("q3", "video000003")),
        currentIndex = 1,
        positionMs = 42_000,
        intent = intent,
        version = 12,
        batchReceipts = listOf(BatchReceipt("prior-import", "a".repeat(64), 123)),
    )
}

private data class StartupFixture(
    val source: StartupSource,
    val store: StartupStore,
    val player: StartupPlayer,
    val coordinator: PlaybackCoordinator,
)

private class StartupSource : YouTubeSource {
    val requested = mutableListOf<String>()
    var onResolve: () -> Unit = {}
    override suspend fun resolve(videoId: String): ResolvedVideo {
        onResolve()
        requested += videoId
        return ResolvedVideo(videoId, "Fixture $videoId", 300,
            StreamSpec("https://media.invalid/$videoId.mp4", "video/mp4", emptyMap()), null, "fixture")
    }
}

private class StartupStore(var state: QueueState) : QueueStore {
    var writes = 0
    var failWrites = false
    override fun read() = state
    override fun write(state: QueueState) {
        if (failWrites) throw IOException("Fixture storage failure")
        writes++
        this.state = state
    }
}

private data class StartupPreparation(val videoId: String, val positionMs: Long, val playWhenReady: Boolean)

private class StartupPlayer : PlaybackPlayer {
    val prepared = mutableListOf<StartupPreparation>()
    var playCalls = 0
    override fun setListener(listener: PlaybackPlayer.Listener) = Unit
    override fun prepare(generation: Long, video: ResolvedVideo, positionMs: Long, playWhenReady: Boolean) {
        prepared += StartupPreparation(video.videoId, positionMs, playWhenReady)
    }
    override fun play() { playCalls++ }
    override fun pause() = Unit
    override fun stop() = Unit
    override fun currentPositionMs() = 42_000L
    override fun release() = Unit
}

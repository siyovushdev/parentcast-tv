package dev.siyovush.parentcast.tv.prepared

import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class PreparedLibraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val first = "video000001"
    private val second = "video000002"
    private fun video(id: String) = ResolvedVideo(id, "<script>Title</script>", 20,
        StreamSpec("https://example.invalid/not-used", "video/mp4", emptyMap()), null, "synthetic")
    private fun store() = PreparedStore(temporary.newFolder())
    private fun downloader() = TrackDownloader { _, target, allowance, progress ->
        check(allowance >= 3) { "NO_SPACE" }; target.writeBytes(byteArrayOf(1, 2, 3)); progress(3, 3)
        LocalTrack(target.name, 3, sha256(target))
    }
    @Test fun completeResponseOnlyAndLimitsAndCancellation() {
        val f = File(temporary.root, "test.part")
        assertEquals(3, copyCompleteTrack(ByteArrayInputStream(byteArrayOf(1, 2, 3)), f, 3, 3, { FREE_RESERVE + 20 }))
        for ((data, total, limit) in listOf(Triple(byteArrayOf(1), 3L, 3L), Triple(byteArrayOf(1,2,3), 2L, 3L), Triple(byteArrayOf(1), -1L, 3L), Triple(byteArrayOf(1,2,3), 3L, 2L))) {
            f.delete()
            assertThrows(IllegalStateException::class.java) { copyCompleteTrack(ByteArrayInputStream(data), f, total, limit, { FREE_RESERVE + 20 }) }
            assertFalse(f.exists())
        }
        assertThrows(IllegalStateException::class.java) { copyCompleteTrack(ByteArrayInputStream(byteArrayOf(1)), f, 1, 3, { FREE_RESERVE }) }
        assertThrows(CancellationException::class.java) { copyCompleteTrack(ByteArrayInputStream(byteArrayOf(1)), f, 1, 3, { FREE_RESERVE + 20 }, { throw CancellationException() }) }
        assertFalse(f.exists())
    }
    @Test fun orderedWholeFilesAndDuplicateIdsReuseOneDownload() = runBlocking {
        val order = mutableListOf<String>(); val storage = store()
        val library = PreparedLibrary(storage, object : YouTubeSource { override suspend fun resolve(videoId: String) = video(videoId).also { order += videoId } }, downloader(), this, { _, _, _ -> })
        library.configure(true, listOf(first, second, first))
        library.runWorker()
        assertEquals(listOf(first, second), order)
        assertEquals(3, library.snapshot().plan.count { library.snapshot().entries[it]?.status == PreparationStatus.READY })
        val local = library.localOrWait(first)!!
        assertTrue(local.video.url.startsWith("file:")); assertEquals("<script>Title</script>", local.title)
        assertEquals(library.snapshot().plan, storage.read().plan)
        val recovered = PreparedLibrary(storage, object : YouTubeSource { override suspend fun resolve(videoId: String): ResolvedVideo = error("No network allowed") }, downloader(), this, { _, _, _ -> })
        assertTrue(recovered.localOrWait(first)!!.video.url.startsWith("file:"))
        storage.track(first).writeText("damaged")
        assertThrows(YouTubeSourceException::class.java) { runBlocking { recovered.localOrWait(first) } }
        assertEquals("FILE_DAMAGED", recovered.snapshot().entries[first]!!.error)
    }
    @Test fun partialResponseNeverReadyAndRetryDoesNotDuplicateQueue() = runBlocking {
        var attempts = 0; val storage = store()
        val library = PreparedLibrary(storage, object : YouTubeSource { override suspend fun resolve(videoId: String) = video(videoId) },
            TrackDownloader { spec, target, allowance, progress ->
                if (++attempts == 1) { File(target.path + ".part").writeText("incomplete"); error("DOWNLOAD_FAILED") }
                downloader().download(spec, target, allowance, progress)
            }, this, { _, _, _ -> })
        library.configure(true, listOf(first)); library.runWorker()
        assertEquals(PreparationStatus.ERROR, library.snapshot().entries[first]!!.status)
        assertFalse(File(storage.track(first).path + ".part").exists())
        library.retry(); library.runWorker()
        assertEquals(PreparationStatus.READY, library.snapshot().entries[first]!!.status)
        assertEquals(listOf(first), library.snapshot().plan)
    }
    @Test fun noSpaceStopsPreparationAndDoesNotEvictFutureOrCurrentFiles() = runBlocking {
        val storage = store()
        val library = PreparedLibrary(storage, object : YouTubeSource { override suspend fun resolve(videoId: String) = video(videoId) }, downloader(), this, { _, _, _ -> }, limit = 3)
        library.configure(true, listOf(first, second)); library.runWorker()
        assertTrue(library.snapshot().paused)
        assertTrue(storage.verify(library.snapshot().entries[first]!!))
        assertEquals("NO_SPACE", library.snapshot().entries[second]!!.error)
        assertFalse(library.hasPending())
    }
    @Test fun cancelWaitAndWorkerCannotPublishLateReady() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val storage = store()
        val library = PreparedLibrary(storage, object : YouTubeSource { override suspend fun resolve(videoId: String) = video(videoId) },
            TrackDownloader { _, target, _, _ -> File(target.path + ".part").writeText("partial"); entered.complete(Unit); awaitCancellation() }, this, { _, _, _ -> })
        library.configure(true, listOf(first))
        val job = launch { library.runWorker() }; entered.await()
        val streaming = object : YouTubeSource { override suspend fun resolve(videoId: String): ResolvedVideo = error("Never fall back to streaming") }
        val waiting = launch { PreparedYouTubeSource(library, streaming).resolve(first) }
        yield(); assertTrue(waiting.isActive)
        waiting.cancelAndJoin() // The same cancellable boundary used by coordinator STOP/NEXT.
        library.pause(); job.join()
        assertEquals(PreparationStatus.WAITING, library.snapshot().entries[first]!!.status)
        assertFalse(storage.track(first).exists())
        assertFalse(File(storage.track(first).path + ".part").exists())
    }
    @Test fun cancelledSocketIOExceptionRemainsWaitingRatherThanDownloadError() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val storage = store()
        val library = PreparedLibrary(storage, object : YouTubeSource { override suspend fun resolve(videoId: String) = video(videoId) },
            TrackDownloader { _, _, _, _ ->
                entered.complete(Unit)
                try { awaitCancellation() } catch (_: CancellationException) { throw java.io.IOException("Socket closed") }
            }, this, { _, _, _ -> })
        library.configure(true, listOf(first))
        val job = launch { library.runWorker() }; entered.await(); library.pause(); job.join()
        assertEquals(PreparationStatus.WAITING, library.snapshot().entries[first]!!.status)
        assertEquals("", library.snapshot().entries[first]!!.error)
    }

    @Test fun removingActiveVideoCancelsItBeforePreparingNext() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val storage = store()
        val library = PreparedLibrary(storage, object : YouTubeSource { override suspend fun resolve(videoId: String) = video(videoId) },
            TrackDownloader { spec, target, allowance, progress ->
                if (target.name.startsWith(first)) { entered.complete(Unit); awaitCancellation() }
                downloader().download(spec, target, allowance, progress)
            }, this, { _, _, _ -> })
        library.configure(true, listOf(first, second))
        val old = launch { library.runWorker() }; entered.await()
        library.schedule(listOf(second)); old.join(); library.runWorker()
        assertEquals(PreparationStatus.WAITING, library.snapshot().entries[first]!!.status)
        assertEquals(PreparationStatus.READY, library.snapshot().entries[second]!!.status)
        assertFalse(storage.track(first).exists())
    }

    @Test fun readyAfterStopDoesNotPrepareOrStartPlayback() = runBlocking {
        val storage = store()
        val library = PreparedLibrary(storage, object : YouTubeSource { override suspend fun resolve(videoId: String) = video(videoId) }, downloader(), this, { _, _, _ -> })
        library.configure(true, listOf(first))
        val networkForbidden = object : YouTubeSource { override suspend fun resolve(videoId: String): ResolvedVideo = error("Streaming must never be called") }
        var preparations = 0
        val player = object : dev.siyovush.parentcast.tv.playback.PlaybackPlayer {
            override fun setListener(listener: dev.siyovush.parentcast.tv.playback.PlaybackPlayer.Listener) = Unit
            override fun prepare(generation: Long, video: ResolvedVideo, positionMs: Long, playWhenReady: Boolean) { preparations++ }
            override fun play() = Unit
            override fun pause() = Unit
            override fun stop() = Unit
            override fun currentPositionMs() = 0L
            override fun release() = Unit
        }
        val queue = dev.siyovush.parentcast.tv.queue.AtomicQueueStore(File(temporary.newFolder(), "queue.json"))
        val coordinator = dev.siyovush.parentcast.tv.playback.PlaybackCoordinator(PreparedYouTubeSource(library, networkForbidden), queue, player, this, Dispatchers.Unconfined)
        coordinator.start(); coordinator.add(first); coordinator.play(); yield()
        assertEquals(0, preparations)
        coordinator.stop(); library.runWorker(); yield()
        assertEquals(0, preparations)
        assertEquals(dev.siyovush.parentcast.tv.playback.PlaybackStatus.STOPPED, coordinator.currentSnapshot().status)
        coordinator.play(); yield()
        assertEquals(1, preparations)
        assertTrue(coordinator.currentSnapshot().localPlayback)
        coordinator.stop()
        assertFalse(coordinator.currentSnapshot().localPlayback)
        coordinator.close()
    }

    @Test fun killedPartialRestartsAndCleanupPreservesPlan() = runBlocking {
        val storage = store()
        storage.track(first).writeText("uncommitted")
        storage.write(PreparationState(true, plan = listOf(first), entries = mapOf(first to PreparedEntry(first, PreparationStatus.DOWNLOADING))))
        val library = PreparedLibrary(storage, object : YouTubeSource { override suspend fun resolve(videoId: String) = video(videoId) }, downloader(), this, { _, _, _ -> })
        assertEquals(PreparationStatus.WAITING, library.snapshot().entries[first]!!.status)
        library.runWorker(); assertEquals(3, storage.track(first).length())
        library.schedule(listOf(second)); yield(); library.runWorker()
        assertTrue(storage.track(first).exists()); assertTrue(storage.track(second).isFile)
        assertEquals(1, library.cleanup())
        assertFalse(storage.track(first).exists()); assertTrue(storage.track(second).isFile)
    }
}

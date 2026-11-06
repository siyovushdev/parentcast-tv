package dev.siyovush.parentcast.tv.home

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import dev.siyovush.parentcast.tv.prepared.*
import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HomeAdminTest {
    @get:Rule val temp = TemporaryFolder()
    private val first = "video000001"
    private val second = "video000002"
    private fun source(audioSize: Long? = 120) = object : YouTubeSource {
        override suspend fun resolve(videoId: String) = ResolvedVideo(videoId, "<script>safe text</script>", 19,
            StreamSpec("https://unused.invalid/video", "video/mp4", emptyMap(), 80),
            StreamSpec("https://unused.invalid/audio", "audio/mp4", emptyMap(), audioSize), "synthetic")
    }
    private val downloader = TrackDownloader { spec, file, _, progress ->
        val size = if (spec.mimeType == "video/mp4") 80 else 120
        file.writeBytes(ByteArray(size)); progress(size / 2L, size.toLong()); progress(size.toLong(), size.toLong())
        LocalTrack(file.name, size.toLong(), sha256(file))
    }
    @Test fun qrDecodesWithoutCredentialsAndRejectsSecretParameters() {
        val url = "http://parentcast-tv.local:8765/"
        val matrix = panelQrMatrix(url)
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))
        assertEquals(url, MultiFormatReader().decode(bitmap).text)
        listOf("http://host:8765/?token=secret", "http://pin@host:8765/", "https://host:8765/", "http://host:8765/#pin").forEach {
            assertThrows(IllegalArgumentException::class.java) { panelQrMatrix(it) }
        }
    }
    @Test fun percentageCoversVideoAndAudioAndNeverFinishesBeforeVerification() = runBlocking {
        val store = PreparedStore(temp.newFolder()); val percentages = mutableListOf<Int?>()
        lateinit var library: PreparedLibrary
        val recording = TrackDownloader { spec, file, allowance, progress ->
            downloader.download(spec, file, allowance) { bytes, total -> progress(bytes, total); percentages += preparationPercent(library.snapshot().entries.getValue(first)) }
        }
        library = PreparedLibrary(store, source(), recording, this, { _, _, _ ->
            assertNotEquals(100, preparationPercent(library.snapshot().entries.getValue(first)))
        })
        library.configure(true, listOf(first)); library.runWorker()
        assertEquals(listOf(20, 40, 70, 99), percentages)
        assertEquals(100, preparationPercent(library.snapshot().entries.getValue(first)))
        assertEquals(200, library.snapshot().entries.getValue(first).total)
        assertEquals(10L * 1024 * 1024 * 1024, library.limit)
    }
    @Test fun unknownFutureAudioSizeStaysIndeterminateUntilKnown() = runBlocking {
        val seen = mutableListOf<Int?>(); lateinit var library: PreparedLibrary
        val d = TrackDownloader { spec, file, allowance, progress -> downloader.download(spec, file, allowance) { bytes, total ->
            progress(bytes, total); seen += preparationPercent(library.snapshot().entries.getValue(first))
        } }
        library = PreparedLibrary(PreparedStore(temp.newFolder()), source(null), d, this, { _, _, _ -> })
        library.configure(true, listOf(first)); library.runWorker()
        assertEquals(listOf(null, null, 70, 99), seen)
    }
    @Test fun deleteOnePreservesOtherFilesAndPlanThenCanPrepareAgain() = runBlocking {
        val store = PreparedStore(temp.newFolder()); val library = PreparedLibrary(store, source(), downloader, this, { _, _, _ -> })
        library.configure(true, listOf(first, second)); library.runWorker()
        library.deleteMedia(first); while (library.snapshot().deleting.isNotEmpty()) yield()
        assertFalse(store.track(first).exists()); assertFalse(store.track(first, true).exists())
        assertTrue(store.verify(library.snapshot().entries.getValue(second)))
        assertEquals(listOf(first, second), library.snapshot().plan)
        assertEquals(PreparationStatus.DELETED, library.snapshot().entries.getValue(first).status)
        assertThrows(YouTubeSourceException::class.java) { runBlocking { library.localOrWait(first) } }
        library.configure(true, listOf(first, second)); library.runWorker()
        assertTrue(store.verify(library.snapshot().entries.getValue(first)))
    }
    @Test(timeout=5000) fun deletionSurvivesRestartAndLeavesSiblingPrivateDataUntouched() = runBlocking {
        val root = temp.newFolder(); val store = PreparedStore(File(root, "media")); val sentinel = File(root, "parent-auth.xml").apply { writeText("synthetic private sentinel") }
        store.track(first).writeText("incomplete"); File(store.track(second).path + ".part").writeText("partial")
        store.write(PreparationState(true, true, listOf(first), mapOf(first to PreparedEntry(first)), deleting = listOf(first, second)))
        val library = PreparedLibrary(store, source(), downloader, this, { _, _, _ -> })
        while (library.snapshot().deleting.isNotEmpty()) yield()
        assertEquals(0, store.usedBytes()); assertEquals("synthetic private sentinel", sentinel.readText())
        assertEquals(PreparationStatus.DELETED, library.snapshot().entries.getValue(first).status)
    }
    @Test fun fullDeleteCancelsActiveWorkAndCleansOrphanPartials() = runBlocking {
        val store = PreparedStore(temp.newFolder()); val entered = CompletableDeferred<Unit>()
        val library = PreparedLibrary(store, source(), TrackDownloader { _, file, _, _ ->
            File(file.path + ".part").writeText("partial"); entered.complete(Unit); awaitCancellation()
        }, this, { _, _, _ -> })
        library.configure(true, listOf(first))
        val work = launch { library.runWorker() }; entered.await()
        File(store.track(second).path + ".part").writeText("orphan")
        library.deleteMedia(); work.join(); while (library.snapshot().deleting.isNotEmpty()) yield()
        assertEquals(0, store.usedBytes()); assertTrue(library.snapshot().paused)
        assertEquals(PreparationStatus.DELETED, library.snapshot().entries.getValue(first).status)
        assertFalse(File(store.track(second).path + ".part").exists())
    }
    @Test fun dynamicHostAndOriginAllowOnlyCurrentBoxNames() {
        var hosts = setOf("192.168.1.10", "parentcast-tv.local")
        val security = dev.siyovush.parentcast.tv.web.RequestSecurity(emptySet(), 8765) { hosts }
        assertTrue(security.isHostAllowed("parentcast-tv.local:8765")); assertTrue(security.isOriginAllowed("http://parentcast-tv.local:8765"))
        hosts = setOf("10.0.0.5", "parentcast-tv.local")
        assertFalse(security.isHostAllowed("192.168.1.10:8765")); assertFalse(security.isOriginAllowed("http://evil.local:8765"))
        assertTrue(security.isHostAllowed("10.0.0.5:8765"))
    }
    @Test fun failedDeletionIsVisibleAndRetryCompletesWithoutRedownload() = runBlocking {
        val store = PreparedStore(temp.newFolder())
        val blocked = store.track(first).apply { mkdir() }
        val blocker = File(blocked, "synthetic-blocker").apply { writeText("test") }
        val library = PreparedLibrary(store, source(), downloader, this, { _, _, _ -> })
        library.deleteMedia(first)
        while (library.snapshot().operationError.isEmpty()) yield()
        assertEquals("DELETE_FAILED", library.snapshot().operationError)
        assertEquals(listOf(first), PreparedStore(store.directory).read().deleting)
        blocker.delete(); library.retryDeletion()
        while (library.snapshot().deleting.isNotEmpty()) yield()
        assertFalse(blocked.exists()); assertEquals("", library.snapshot().operationError)
        assertTrue(library.snapshot().paused)
    }
}

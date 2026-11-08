package dev.siyovush.parentcast.tv.prepared

import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IntegrityRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private val id = "video000001"
    private fun source() = object : YouTubeSource {
        override suspend fun resolve(videoId:String)=ResolvedVideo(videoId,"Test",12,StreamSpec("https://unused.invalid/v","video/mp4",emptyMap()),null,"test")
    }
    @Test fun corruptUnpublishedFileIsNeverReadyAndFreshResolveCanRepairIt() = runBlocking {
        val store=PreparedStore(temp.newFolder());var downloads=0;var sawVerify=false
        lateinit var library: PreparedLibrary
        library=PreparedLibrary(store,source(),TrackDownloader {_,file,_,_->
            downloads++;file.writeText(if(downloads==1)"broken" else "valid");LocalTrack(file.name,file.length(),sha256(file))
        },this,{file,_,_->
            sawVerify=sawVerify || library.snapshot().entries.getValue(id).phase=="VERIFY"
            assertNotEquals(PreparationStatus.READY,library.snapshot().entries.getValue(id).status)
            if(file.readText()=="broken")throw MediaIntegrityFailure("MEDIA_CORRUPT")
        },retryDelay={1})
        library.configure(true,listOf(id));library.runWorker()
        assertTrue(sawVerify);assertEquals(2,downloads);assertEquals(PreparationStatus.READY,library.snapshot().entries.getValue(id).status)
    }
    @Test fun repeatedCorruptionStopsAfterTwoRetriesAndSurvivesReload() = runBlocking {
        val store=PreparedStore(temp.newFolder());var downloads=0
        val library=PreparedLibrary(store,source(),TrackDownloader {_,file,_,_->downloads++;file.writeText("bad");LocalTrack(file.name,3,sha256(file))},this,
            {_,_,_->throw MediaIntegrityFailure("MEDIA_CORRUPT")},retryDelay={1})
        library.configure(true,listOf(id));library.runWorker()
        val state=store.read();assertEquals(3,downloads);assertTrue(state.paused)
        assertEquals(3,state.entries.getValue(id).integrityAttempts);assertEquals(PreparationStatus.ERROR,state.entries.getValue(id).status)
        assertEquals("MEDIA_CORRUPT",state.entries.getValue(id).error);assertFalse(store.track(id).exists())
        library.runWorker();assertEquals(3,downloads)
    }
    @Test fun previouslyPublishedCopyIsPreservedOnGeneralRetry() = runBlocking {
        val store=PreparedStore(temp.newFolder());val file=store.track(id);file.writeText("original")
        val track=LocalTrack(file.name,file.length(),sha256(file))
        store.write(PreparationState(enabled=true,plan=listOf(id),entries=mapOf(id to PreparedEntry(id,PreparationStatus.ERROR,tracks=listOf(track),error="FILE_DAMAGED"))))
        var downloads=0
        val library=PreparedLibrary(store,source(),TrackDownloader {_,_,_,_->downloads++;error("Must not overwrite original")},this,{_,_,_->})
        library.retry();library.runWorker()
        assertEquals(0,downloads);assertEquals("original",file.readText());assertTrue(library.snapshot().paused)
        assertEquals(listOf(track),library.snapshot().entries.getValue(id).tracks)
    }
    @Test fun pauseDuringVerificationKeepsDownloadWithoutPublishingIt() = runBlocking {
        val store=PreparedStore(temp.newFolder());val verifying=CompletableDeferred<Unit>()
        val library=PreparedLibrary(store,source(),TrackDownloader {_,file,_,_->file.writeText("unverified");LocalTrack(file.name,file.length(),sha256(file))},this,
            {_,_,_->verifying.complete(Unit);awaitCancellation()})
        library.configure(true,listOf(id));val worker=launch {library.runWorker()};verifying.await();library.pause();worker.join()
        assertEquals(PreparationStatus.WAITING,library.snapshot().entries.getValue(id).status)
        assertTrue(store.track(id).exists());assertTrue(library.snapshot().paused)
    }
}

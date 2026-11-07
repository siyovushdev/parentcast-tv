package dev.siyovush.parentcast.tv.prepared

import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RetryRecoveryTest {
    @get:Rule val temp=TemporaryFolder()
    private val first="video000001";private val second="video000002"
    private fun video(id: String)=ResolvedVideo(id,"Test",10,StreamSpec("https://unused.invalid/video","video/mp4",emptyMap()),null,"test")
    @Test fun networkRetryReResolvesAutomaticallyAndPrioritizesFirstProgrammeItem() = runBlocking {
        val store=PreparedStore(temp.newFolder());val order=mutableListOf<String>();var failed=false
        val library=PreparedLibrary(store,object:YouTubeSource{override suspend fun resolve(videoId:String)=video(videoId).also {order+=videoId}},
            TrackDownloader { _,file,_,progress ->
                if(file.name.startsWith(first) && !failed) { failed=true;File(file.path+".part").writeText("kept");throw IOException("synthetic disconnection") }
                if(file.name.startsWith(first)) assertEquals("kept",File(file.path+".part").readText())
                file.writeText("ready");File(file.path+".part").delete();progress(5,5);LocalTrack(file.name,5,sha256(file))
            },this,{_,_,_->},retryDelay={20})
        library.configure(true,listOf(first,second));library.runWorker()
        assertEquals(listOf(first,first,second),order)
        assertTrue(library.snapshot().entries.values.all {it.status==PreparationStatus.READY})
        assertEquals(100,preparationPercent(library.snapshot().entries.getValue(first)))
    }
    @Test fun pauseCancelsLongRetryWaitAndRestartKeepsRetainedBytes() = runBlocking {
        val store=PreparedStore(temp.newFolder());val failed=CompletableDeferred<Unit>()
        val library=PreparedLibrary(store,object:YouTubeSource{override suspend fun resolve(videoId:String)=video(videoId)},TrackDownloader { _,file,_,_->
            File(file.path+".part").writeText("retained");failed.complete(Unit);throw IOException("network")
        },this,{_,_,_->},retryDelay={300_000})
        library.configure(true,listOf(first));val worker=launch {library.runWorker()};failed.await()
        while(library.snapshot().entries[first]?.status!=PreparationStatus.RETRY_WAIT)yield()
        assertEquals(8L,store.read().entries[first]?.bytes)
        library.pause();worker.join();assertTrue(library.snapshot().paused)
        val restored=store.read();assertEquals(8L,restored.entries[first]?.bytes);assertTrue(File(store.track(first).path+".part").exists())
        assertFalse(library.hasPending())
    }
    @Test fun authorizationAndUnsupportedFailuresNeverEnterAutomaticRetryLoop() = runBlocking {
        for(code in listOf("NO_SPACE","UNSUPPORTED")) {
            var calls=0;val store=PreparedStore(temp.newFolder())
            val library=PreparedLibrary(store,object:YouTubeSource{override suspend fun resolve(videoId:String)=video(videoId)},TrackDownloader {_,_,_,_->calls++;error(code)},this,{_,_,_->},retryDelay={1})
            library.configure(true,listOf(first));library.runWorker();assertEquals(1,calls);assertEquals(PreparationStatus.ERROR,library.snapshot().entries[first]?.status)
        }
    }
}

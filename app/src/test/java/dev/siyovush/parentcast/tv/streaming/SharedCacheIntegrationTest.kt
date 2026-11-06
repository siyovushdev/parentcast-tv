package dev.siyovush.parentcast.tv.streaming

import android.content.Context
import androidx.media3.datasource.DataSpec
import dev.siyovush.parentcast.tv.media.*
import dev.siyovush.parentcast.tv.playback.PlaybackErrorKind
import dev.siyovush.parentcast.tv.playback.PlaybackFailure
import dev.siyovush.parentcast.tv.prepared.*
import dev.siyovush.parentcast.tv.queue.*
import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import androidx.media3.common.C
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** Real Media3 SimpleCache/DataSource integration with synthetic HTTP bytes, not a YouTube/device test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],manifest=Config.NONE)
class SharedCacheIntegrationTest {
    @get:Rule val temporary=TemporaryFolder()
    private val id="video000001"
    private val quality="a".repeat(64)
    private val video=ByteArray((StreamingLibrary.BLOCK*2+123).toInt()){(it%251).toByte()}
    private val audio=ByteArray(8000){(it%197).toByte()}
    private inner class Fixture(val root:File=temporary.newFolder(),val limit:Long=20_000_000,val separateAudio:Boolean=false):Closeable {
        val server=MockWebServer()
        val ranges=CopyOnWriteArrayList<Triple<String,Long,Long>>()
        var revision="one"
        var expireOnce=false
        var expired=false
        var metadataFailure:PlaybackFailure?=null
        var brokenAudio=false
        var slow=false
        var rejectValidation=false
        var mounted=true
        var writable=true
        val card=temporary.newFolder()
        val catalogCalls=AtomicInteger()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val store=PreparedStore(root,{listOf(MediaVolume("sd:fixture","fixture card",card.takeIf{mounted},true,mounted,writable))},internalLimit=limit)
        val fallback=object:YouTubeSource {override suspend fun resolve(videoId:String):ResolvedVideo=error("Unexpected legacy network request")}
        val library=PreparedLibrary(store,fallback,TrackDownloader{_,_,_,_->error("Legacy downloader must not run")},scope,{_,_,_->check(!rejectValidation) { "UNSUPPORTED" }},limit=limit)
        val streaming:StreamingLibrary
        init {
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if((expireOnce || expired) && request.path!!.contains("old")){expireOnce=false;return MockResponse().setResponseCode(403)}
                val name=if(request.path!!.startsWith("/audio"))"audio" else "video"
                if(brokenAudio && name=="audio" && request.getHeader("Range")!="bytes=0-0") {
                    val until=System.nanoTime()+5_000_000_000L
                    while(!store.track(id).isFile && System.nanoTime()<until)Thread.sleep(10)
                    return MockResponse().setResponseCode(503)
                }
                val data=if(name=="audio")audio else video
                val match=Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.getHeader("Range").orEmpty()) ?: return MockResponse().setResponseCode(400)
                val start=match.groupValues[1].toLong();val end=minOf(match.groupValues[2].toLong(),data.size-1L)
                ranges+=Triple(name,start,end)
                return MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${data.size}")
                    .setHeader("ETag","\"$revision\"").setBody(Buffer().write(data,(start).toInt(),(end-start+1).toInt())).apply { if(slow)throttleBody(16384,100,TimeUnit.MILLISECONDS) }
            } }
            server.start()
            val source=QualitySource { key->
                metadataFailure?.let { throw YouTubeSourceException(it) }
                val generation=catalogCalls.incrementAndGet();val suffix=if(generation==1)"old" else "fresh"
                val v=StreamSpec(server.url("/video/$suffix").toString(),"video/mp4",emptyMap(),video.size.toLong(),"video-format")
                val a=if(separateAudio)StreamSpec(server.url("/audio/$suffix").toString(),"audio/mp4",emptyMap(),audio.size.toLong(),"audio-format") else null
                VideoPreview(key,"Fixture",20,listOf(VideoQuality(quality,720,video.size.toLong()+if(separateAudio)audio.size else 0,ResolvedVideo(key,"Fixture",20,v,a,"fixture"))))
            }
            streaming=StreamingLibrary(RuntimeEnvironment.getApplication(),library,source,RangeTransport(DisabledYouTubeSession,OkHttpClient()){_,u->u.host==server.hostName})
        }
        suspend fun start(storageId:String="internal")=streaming.start(id,quality,storageId,store.storage.version())
        suspend fun read(position:Long,length:Int):ByteArray {
            val spec=streaming.source(fallback).resolve(id).video
            val source=streaming.dataSourceFactory(spec)!!.createDataSource()
            val output=ByteArray(length);var at=0
            try {
                source.open(DataSpec.Builder().setUri(spec.url).setPosition(position).setLength(length.toLong()).build())
                while(at<length){val n=source.read(output,at,length-at);check(n>0);at+=n}
            }finally{source.close()}
            return output
        }
        override fun close(){scope.cancel();streaming.close();server.close()}
    }
    @Test fun seekThenBackgroundSharesRangesAndOnlyPublishesReadyAfterValidation()=runBlocking {
        Fixture(separateAudio=true).use { f->
            f.start();assertFalse(f.library.mediaReadiness(id)=="READY")
            assertArrayEquals(video.copyOfRange(900_000,900_100),f.read(900_000,100))
            assertArrayEquals(video.copyOfRange(20,120),f.read(20,100))
            assertTrue(f.streaming.state(id)!!.downloadedBytes>=StreamingLibrary.BLOCK*2)
            f.library.runWorker()
            assertEquals("READY",f.library.mediaReadiness(id));assertTrue(f.store.verify(f.library.snapshot().entries.getValue(id)))
            assertArrayEquals(video,f.store.track(id).readBytes());assertArrayEquals(audio,f.store.track(id,true).readBytes())
            for(name in listOf("video","audio")) {
                val fetched=f.ranges.filter { it.first==name && !(it.second==0L && it.third==0L) }.sumOf { it.third-it.second+1 }
                assertEquals("One body, shared by seek/player/background: $name",(if(name=="video")video.size else audio.size).toLong(),fetched)
            }
            assertEquals(video.size.toLong()+audio.size,f.store.usedBytes())
        }
    }
    @Test fun restartReusesCachedRangeAndDoesNotResumePlayback()=runBlocking {
        val root=temporary.newFolder()
        Fixture(root).use { f->f.start();f.read(20,30);assertFalse(f.library.mediaReadiness(id)=="READY") }
        Fixture(root).use { f->
            f.start();assertArrayEquals(video.copyOfRange(20,50),f.read(20,30))
            assertEquals(0,f.ranges.count { it.third>it.second })
            f.library.runWorker();assertEquals("READY",f.library.mediaReadiness(id))
        }
    }
    @Test fun repeatStartAndDeleteThenRedownloadAreSafe()=runBlocking {
        Fixture().use { f->
            f.start();f.read(0,10);val bytes=f.streaming.state(id)!!.downloadedBytes
            f.start();assertEquals(bytes,f.streaming.state(id)!!.downloadedBytes)
            f.library.runWorker();f.streaming.delete(listOf(id));f.store.deleteChecked(id)
            // New catalogue after explicit file deletion, preserving the service's cache implementation.
            f.library.deleteMedia(id);withTimeout(3000){while(f.library.snapshot().deleting.isNotEmpty())delay(10)}
            f.start();assertArrayEquals(video.copyOfRange(0,10),f.read(0,10))
        }
    }
    @Test fun signedUrlExpiryRefreshesSameRepresentationButNeverMixesChangedBytes()=runBlocking {
        Fixture().use { f->
            f.start();f.expireOnce=true;assertArrayEquals(video.copyOfRange(0,10),f.read(0,10));assertTrue(f.catalogCalls.get()>=2)
        }
        Fixture().use { f->
            f.start();f.revision="different";f.expireOnce=true
            val error=runCatching { f.read(0,10) }.exceptionOrNull();assertNotNull(error)
            assertEquals("REPRESENTATION_CHANGED",f.streaming.state(id)!!.error);assertFalse(f.streaming.canPlayDuringDownload(id));assertFalse(f.library.mediaReadiness(id)=="READY")
        }
    }
    @Test fun materializedVideoSurvivesAudioFailureAndRestart()=runBlocking {
        val root=temporary.newFolder()
        Fixture(root,trueLimit(),true).use { f->
            f.start();f.brokenAudio=true
            assertNotNull(runCatching{f.streaming.complete(id){_,_,_->}}.exceptionOrNull())
            assertTrue(f.store.track(id).isFile);assertTrue(ResumeFiles(f.store.track(id)).read()!!.complete)
            // Actual audio HTTP failure after video materialization, then process recreation.
        }
        Fixture(root,trueLimit(),true).use { f->
            f.start();f.library.runWorker();assertEquals("READY",f.library.mediaReadiness(id))
            assertEquals(0,f.ranges.count { it.first=="video" && it.third>it.second })
        }
    }
    @Test fun quotaIncludesTemporaryFinalCopyAndNeverErasesReadyContent()=runBlocking {
        Fixture(limit=video.size.toLong()+10000).use { f->
            val keep=File(f.root,"keep.video.mp4").apply{writeText("preserve")}
            assertEquals("NO_SPACE",runCatching{f.start()}.exceptionOrNull()!!.message)
            assertEquals("preserve",keep.readText());assertFalse(f.library.mediaReadiness(id)=="READY")
        }
    }
    @Test fun explicitSubsetAndSingleStartDoNotDownloadUnselectedQueue()=runBlocking {
        Fixture().use { f->
            val ids=listOf(id,"video000002","video000003")
            val q=QueueState(ids.mapIndexed { index,key->QueueItem("q$index",key) },currentIndex=0)
            f.library.configure(true,listOf(ids[1]),explicitSelection=true);f.library.syncQueue(q)
            assertEquals(listOf(ids[1]),f.library.snapshot().plan);assertTrue(f.store.read().explicitSelection)
            f.library.pause();f.start();f.library.syncQueue(q)
            assertEquals(listOf(id),f.library.snapshot().plan)
            f.library.configure(true,listOf(ids[1],ids[2]));f.start();f.library.syncQueue(q)
            assertEquals(listOf(id,ids[1],ids[2]),f.library.snapshot().plan)
        }
    }
    @Test fun cancellationClosesHttpQuicklyAndResumesFromRetainedRanges()=runBlocking {
        Fixture().use { f->
            f.start();f.slow=true
            val job=launch(Dispatchers.IO){f.streaming.complete(id){_,_,_->}}
            withTimeout(3000){while(f.ranges.none { it.third>it.second })delay(10)}
            delay(150)
            withTimeout(2000){job.cancelAndJoin()}
            assertFalse(f.library.mediaReadiness(id)=="READY")
            assertTrue(f.streaming.state(id)!!.downloadedBytes>0)
            f.slow=false;f.start();f.library.runWorker();assertEquals("READY",f.library.mediaReadiness(id))
            assertArrayEquals(video,f.store.track(id).readBytes())
        }
    }
    @Test fun missingOrReadonlyCardPreservesPlanAndRecoversOnReinsertion()=runBlocking {
        Fixture().use { f->
            f.store.storage.select("sd:fixture",f.store.storage.version());f.start("sd:fixture");f.read(0,20)
            f.mounted=false;assertFalse(f.streaming.canPlayDuringDownload(id))
            assertNotNull(runCatching { f.streaming.complete(id){_,_,_->} }.exceptionOrNull())
            f.mounted=true;f.writable=false;assertFalse(f.streaming.canPlayDuringDownload(id))
            assertNotNull(runCatching { f.start("sd:fixture") }.exceptionOrNull())
            f.writable=true;f.start("sd:fixture");assertArrayEquals(video.copyOfRange(0,20),f.read(0,20));f.library.runWorker()
            assertEquals("READY",f.library.mediaReadiness(id));assertEquals("sd:fixture",f.library.mediaStorageId(id));assertFalse(File(f.root,"$id.video.mp4").exists())
        }
    }
    @Test fun persistedDeletionWaitsForSharedCacheAttachmentAndCannotResurrect()=runBlocking {
        val root=temporary.newFolder()
        Fixture(root).use { f->f.start();f.read(0,20);f.store.write(f.library.snapshot().copy(deleting=listOf(id))) }
        Fixture(root).use { f->
            withTimeout(3000){while(f.library.snapshot().deleting.isNotEmpty())delay(10)}
            assertEquals("DELETED",f.library.mediaReadiness(id));assertFalse(f.streaming.canPlayDuringDownload(id));assertNull(f.streaming.state(id))
        }
    }
    @Test fun sharedPlaybackRetriesLongNetworkOutageButSurfacesIdentityAndQuotaFailures() {
        val policy=SharedLoadErrorPolicy()
        fun info(code:String,count:Int)=LoadErrorHandlingPolicy.LoadErrorInfo(LoadEventInfo(1,DataSpec.Builder().setUri("parentcast-cache://fixture").build(),0),MediaLoadData(C.DATA_TYPE_MEDIA),RangeFailure(code),count)
        assertEquals(Int.MAX_VALUE,policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        assertEquals(30_000L,policy.getRetryDelayMsFor(info("NETWORK_RETRY",100)))
        assertEquals(1_000L,policy.getRetryDelayMsFor(info("URL_EXPIRED",1)))
        val unknown=info("NETWORK_RETRY",4).let { LoadErrorHandlingPolicy.LoadErrorInfo(it.loadEventInfo,it.mediaLoadData,java.io.IOException("unknown file error"),4) }
        assertEquals(C.TIME_UNSET,policy.getRetryDelayMsFor(unknown))
        for(code in listOf("REPRESENTATION_CHANGED","NO_SPACE","STORAGE_READ_ONLY","RANGE_UNSUPPORTED"))assertEquals(C.TIME_UNSET,policy.getRetryDelayMsFor(info(code,1)))
    }
    @Test fun failedFinalValidationInvalidatesMaterializedPointersBeforeRetry()=runBlocking {
        Fixture().use { f->
            f.start();f.rejectValidation=true;f.library.runWorker()
            assertEquals("ERROR",f.library.mediaReadiness(id));assertFalse(f.store.track(id).exists());assertFalse(f.streaming.canPlayDuringDownload(id))
            f.rejectValidation=false;f.start();assertArrayEquals(video.copyOfRange(0,20),f.read(0,20))
            f.library.runWorker();assertEquals("READY",f.library.mediaReadiness(id))
        }
    }
    @Test fun missingMaterializedFileSurfacesStorageErrorInsteadOfRetryLoop()=runBlocking {
        Fixture().use { f->
            f.start();f.streaming.complete(id){_,_,_->};f.store.track(id).delete()
            assertEquals("STORAGE_ERROR",runCatching{f.read(0,20)}.exceptionOrNull()!!.message)
            assertEquals("STORAGE_ERROR",f.streaming.state(id)!!.error)
        }
    }
    @Test fun terminalMetadataRefreshErrorsStopPlaybackAndBackgroundRetries()=runBlocking {
        val cases=listOf(PlaybackFailure(PlaybackErrorKind.UNAVAILABLE) to "UNAVAILABLE",
            PlaybackFailure(PlaybackErrorKind.UNSUPPORTED) to "UNSUPPORTED",
            PlaybackFailure(PlaybackErrorKind.NETWORK,stopRequested=true) to "YOUTUBE_AUTH_REQUIRED")
        for((failure,code) in cases)Fixture().use { f->
            f.start();f.expired=true;f.metadataFailure=failure
            assertEquals(code,runCatching{f.read(0,20)}.exceptionOrNull()!!.message)
            assertEquals(code,f.streaming.state(id)!!.error);assertFalse(f.streaming.canPlayDuringDownload(id))
            withTimeout(3000){f.library.runWorker()}
            assertEquals("ERROR",f.library.mediaReadiness(id));assertEquals(code,f.library.snapshot().entries.getValue(id).error)
            assertFalse(f.library.hasPending())
        }
    }
    @Test fun cacheStorageFailureStopsBackgroundAndPreservesExistingFiles()=runBlocking {
        Fixture().use { f->
            val keep=File(f.root,"keep.video.mp4").apply {writeText("existing video")}
            f.start();File(f.root,"stream-cache").writeText("cache directory cannot be created")
            withTimeout(3000){f.library.runWorker()}
            assertEquals("ERROR",f.library.mediaReadiness(id));assertEquals("STORAGE_ERROR",f.library.snapshot().entries.getValue(id).error)
            assertTrue(f.library.snapshot().paused);assertFalse(f.library.hasPending())
            assertEquals("STORAGE_ERROR",f.streaming.state(id)!!.error);assertFalse(f.streaming.canPlayDuringDownload(id))
            assertEquals("existing video",keep.readText())
        }
    }
    @Test fun realSimpleCacheSpansCountAgainstStorageQuotaBeforeReady()=runBlocking {
        Fixture().use { f->
            f.start();f.read(0,20)
            val cached=f.streaming.state(id)!!.downloadedBytes
            assertEquals(StreamingLibrary.BLOCK,cached)
            assertFalse(f.store.track(id).exists())
            assertTrue(PreparedStorage.mediaBytes(f.root)>=cached)
            assertTrue(f.store.storage.usedBytes(INTERNAL_STORAGE)>=cached)
            val budget=MediaBudget(f.root,limit=cached,storage=f.store.storage)
            var wrote=false
            assertEquals("NO_SPACE",assertThrows(IllegalStateException::class.java){budget.writeCache(INTERNAL_STORAGE,1){wrote=true}}.message)
            assertFalse(wrote);assertEquals(cached,f.streaming.state(id)!!.downloadedBytes)
        }
    }
    private fun trueLimit()=20_000_000L
}

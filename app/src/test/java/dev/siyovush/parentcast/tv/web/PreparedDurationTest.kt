package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.prepared.*
import dev.siyovush.parentcast.tv.playback.*
import dev.siyovush.parentcast.tv.queue.*
import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PreparedDurationTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun waitingDownloadsShowCachedDurationWithoutDownloadingOrChangingReadiness()=runTest {
        val store=PreparedStore(temp.newFolder())
        store.write(PreparationState(plan=listOf("video000001","video000002"),entries=mapOf("video000001" to PreparedEntry("video000001"),"video000002" to PreparedEntry("video000002",duration=88))))
        val source=object:YouTubeSource{override suspend fun resolve(videoId:String):ResolvedVideo=error("No implicit download")}
        val library=PreparedLibrary(store,source,TrackDownloader{_,_,_,_->error("No implicit download")},this,{_,_,_->})
        val p=object:PlaybackPlayer {
            override fun setListener(listener:PlaybackPlayer.Listener){};override fun prepare(generation:Long,video:ResolvedVideo,positionMs:Long,playWhenReady:Boolean){}
            override fun play(){};override fun pause(){};override fun stop(){};override fun currentPositionMs()=0L;override fun release(){}
        }
        val c=PlaybackCoordinator(source,AtomicQueueStore(File(temp.root,"queue.json")),p,this,StandardTestDispatcher(testScheduler))
        val api=PreparedApi(library,c,object:CallDispatcher{override fun <T> call(block:()->T)=block()},{77L})
        val entries=api.state().getJSONArray("entries")
        assertEquals(77L,entries.getJSONObject(0).getLong("durationSeconds"))
        assertEquals(88L,entries.getJSONObject(1).getLong("durationSeconds"))
        assertEquals("WAITING",entries.getJSONObject(0).getString("status"))
        assertFalse(api.canPlay("vk:-1_1"));assertEquals(0,api.state().getInt("ready"))
        c.close()
    }
    @Test fun readyFileInAnotherPlaylistIsStillMarkedAsInQueue()=runTest {
        val first="video000001";val second="video000002";val orphan="video000003"
        val store=PreparedStore(temp.newFolder())
        store.write(PreparationState(plan=listOf(first),entries=listOf(first,second,orphan).associateWith {PreparedEntry(it,PreparationStatus.READY)}))
        val source=object:YouTubeSource{override suspend fun resolve(videoId:String):ResolvedVideo=error("No network")}
        val library=PreparedLibrary(store,source,TrackDownloader{_,_,_,_->error("No download")},this,{_,_,_->})
        val p=object:PlaybackPlayer {
            override fun setListener(listener:PlaybackPlayer.Listener){};override fun prepare(generation:Long,video:ResolvedVideo,positionMs:Long,playWhenReady:Boolean){}
            override fun play(){};override fun pause(){};override fun stop(){};override fun currentPositionMs()=0L;override fun release(){}
        }
        val queue=AtomicQueueStore(File(temp.root,"queue.json"))
        queue.write(QueueState(items=listOf(first,second).map {QueueItem(it,it)},currentIndex=0))
        val c=PlaybackCoordinator(source,queue,p,this,StandardTestDispatcher(testScheduler))
        val files=PreparedApi(library,c,object:CallDispatcher{override fun <T> call(block:()->T)=block()}).state().getJSONArray("files")
        val inQueue=(0 until files.length()).associate {files.getJSONObject(it).let {it.getString("videoId") to it.getBoolean("inQueue")}}
        assertEquals(mapOf(first to true,second to true,orphan to false),inQueue)
        assertTrue(library.snapshot().entries.values.all {it.status==PreparationStatus.READY})
        c.close()
    }
}

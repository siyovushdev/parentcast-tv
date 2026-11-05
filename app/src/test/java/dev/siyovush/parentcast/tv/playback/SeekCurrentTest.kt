package dev.siyovush.parentcast.tv.playback

import dev.siyovush.parentcast.tv.queue.*
import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeekCurrentTest {
    @Test fun seeksForwardBackwardAndKeepsPausePlaylistAndQueueOrder()=runTest {
        val f=fixture();f.c.start();advanceUntilIdle()
        val before=f.c.currentSnapshot();assertTrue(before.seekable);assertEquals(300_000L,before.durationMs)
        assertEquals(SeekResult.APPLIED,f.seek(120_000));assertEquals(120_000,f.p.position)
        assertEquals(PlaybackIntent.PAUSE,f.store.s.intent);assertEquals(before.queueState.groups,f.store.s.groups)
        assertEquals(SeekResult.APPLIED,f.seek(10_000));assertEquals(listOf(120_000L,10_000L),f.p.seeks)
        assertEquals(before.queueState.items,f.store.s.items);assertEquals(before.queueState.activeGroupId,f.store.s.activeGroupId)
        assertEquals(10_000,f.store.s.positionMs);assertEquals(PlaybackIntent.PAUSE,f.c.currentSnapshot().queueState.intent)
        f.c.close()
    }
    @Test fun seekingWhilePlayingDoesNotCallPauseOrPrepareAgain()=runTest {
        val f=fixture(PlaybackIntent.PLAY);f.c.start();advanceUntilIdle();val calls=f.p.prepares
        assertEquals(SeekResult.APPLIED,f.seek(180_000));assertEquals(PlaybackIntent.PLAY,f.store.s.intent)
        assertEquals(calls,f.p.prepares);assertEquals(0,f.p.pauses);f.c.close()
    }
    @Test fun bufferedSeekDoesNotOverwritePlayerStatusOrWaitForAnEventThatMayNotCome()=runTest {
        for(mode in listOf("cached","buffer","ready")){
            val f=fixture(PlaybackIntent.PLAY);f.c.start();advanceUntilIdle();val generation=f.c.currentSnapshot().generation
            f.c.onPlaying(generation)
            f.p.onSeek={if(mode!="cached")f.c.onBuffering(generation);if(mode=="ready")f.c.onPlaying(generation)}
            assertEquals(SeekResult.APPLIED,f.seek(90_000))
            assertEquals(if(mode=="buffer")PlaybackStatus.BUFFERING else PlaybackStatus.PLAYING,f.c.currentSnapshot().status)
            f.c.close()
        }
    }
    @Test fun staleVersionAndGenerationAfterNextCannotSeekAnotherVideo()=runTest {
        val f=fixture();f.c.start();advanceUntilIdle();val old=f.c.currentSnapshot()
        f.c.next();advanceUntilIdle()
        assertEquals(SeekResult.CONFLICT,f.c.seekCurrent("a",old.queueState.version,old.generation,1000))
        val current=f.c.currentSnapshot();assertEquals(SeekResult.CONFLICT,f.c.seekCurrent("b",current.queueState.version,old.generation,1000))
        assertTrue(f.p.seeks.isEmpty());assertEquals("b",f.c.currentSnapshot().queueState.currentItem!!.queueItemId);f.c.close()
    }
    @Test fun unavailableStopAndInvalidPositionsDoNotTouchPlayer()=runTest {
        val f=fixture();f.c.start();advanceUntilIdle()
        for(target in listOf(-1L,300_001L,Long.MAX_VALUE))try{f.seek(target);fail("invalid seek")}catch(_:IllegalArgumentException){}
        f.p.ready=false;assertEquals(SeekResult.UNAVAILABLE,f.seek(1000));f.p.ready=true
        f.c.stop();assertEquals(SeekResult.UNAVAILABLE,f.seek(1000));assertTrue(f.p.seeks.isEmpty());f.c.close()
    }
    @Test fun storageFailureCannotMovePlayerAndSeekFailureRollsBack()=runTest {
        val f=fixture();f.c.start();advanceUntilIdle();val original=f.store.s
        f.store.fail=true;assertEquals(SeekResult.STORAGE_ERROR,f.seek(1000));assertTrue(f.p.seeks.isEmpty());assertEquals(original,f.store.s)
        f.store.fail=false;f.p.fail=true;assertEquals(SeekResult.FAILED,f.seek(1000));assertEquals(original.positionMs,f.store.s.positionMs)
        f.c.close()
    }
    @Test fun seekPersistsForRestartAndDurationEndStaysInsideTheVideo()=runTest {
        val f=fixture();f.c.start();advanceUntilIdle();assertEquals(SeekResult.APPLIED,f.seek(300_000));assertEquals(299_999,f.p.position)
        f.c.close();val p=SeekPlayer();val c=PlaybackCoordinator(source,f.store,p,this,StandardTestDispatcher(testScheduler),autoResumeOnStartup=false)
        c.start();advanceUntilIdle();assertEquals(299_999,p.position);assertEquals(PlaybackIntent.PAUSE,c.currentSnapshot().queueState.intent);c.close()
    }
    @Test fun livePositionIsReadWithoutWritingSnapshotEveryPoll()=runTest {
        val f=fixture();f.c.start();advanceUntilIdle();val writes=f.store.writes
        f.p.position=70_000;assertEquals(70_000,f.c.currentSnapshot().queueState.positionMs);assertEquals(writes,f.store.writes)
        f.c.close()
    }
    private fun TestScope.fixture(intent:PlaybackIntent=PlaybackIntent.PAUSE):SeekFixture {
        val group=QueueGroup("g_"+"b".repeat(32),"Test",listOf("video000001","video000002"),QueueGroupSource.COLLECTION)
        val store=SeekStore(QueueState(listOf(QueueItem("a","video000001"),QueueItem("b","video000002")),0,42_000,intent,groups=listOf(group),activeGroupId=group.id))
        val p=SeekPlayer();return SeekFixture(PlaybackCoordinator(source,store,p,this,StandardTestDispatcher(testScheduler)),store,p)
    }
    companion object {val source=object:YouTubeSource{override suspend fun resolve(videoId:String)=ResolvedVideo(videoId,"Fixture",300,StreamSpec("https://media.invalid/test.mp4","video/mp4",emptyMap()),null,"fixture")}}
}
private class SeekFixture(val c:PlaybackCoordinator,val store:SeekStore,val p:SeekPlayer){fun seek(ms:Long):SeekResult {val s=c.currentSnapshot();return c.seekCurrent(s.queueState.currentItem!!.queueItemId,s.queueState.version,s.generation,ms)}}
private class SeekStore(var s:QueueState):QueueStore{var fail=false;var writes=0;override fun read()=s;override fun write(state:QueueState){if(fail)error("disk failed");s=state;writes++}}
private class SeekPlayer:PlaybackPlayer {
    var position=0L;var ready=false;var fail=false;var prepares=0;var pauses=0;var onSeek:(()->Unit)?=null;val seeks=mutableListOf<Long>()
    override fun setListener(listener:PlaybackPlayer.Listener){}
    override fun prepare(generation:Long,video:ResolvedVideo,positionMs:Long,playWhenReady:Boolean){position=positionMs;ready=true;prepares++}
    override fun play(){};override fun pause(){pauses++};override fun stop(){ready=false};override fun release(){}
    override fun currentPositionMs()=position;override fun durationMs()=if(ready)300_000L else null;override fun isSeekable()=ready
    override fun seekTo(positionMs:Long){if(fail)error("seek failed");position=positionMs;seeks+=positionMs;onSeek?.invoke()}
}

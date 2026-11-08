package dev.siyovush.parentcast.tv.playback

import dev.siyovush.parentcast.tv.queue.*
import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineRecoveryTest {
    private class Store:QueueStore {
        var state=QueueState.Empty.add(QueueItem("q1","video000001")).add(QueueItem("q2","video000002"))
        override fun read()=state
        override fun write(state:QueueState){this.state=state}
    }
    private class Player:PlaybackPlayer {
        val positions=mutableListOf<Long>();var position=423539L;var stops=0
        override fun setListener(listener:PlaybackPlayer.Listener){}
        override fun prepare(generation:Long,video:ResolvedVideo,positionMs:Long,playWhenReady:Boolean){positions+=positionMs}
        override fun play(){}
        override fun pause(){}
        override fun stop(){stops++;position=0}
        override fun currentPositionMs()=position
        override fun release(){}
    }
    private fun TestScope.fixture(online:Boolean=true):Triple<PlaybackCoordinator,Player,Store> {
        val store=Store();val player=Player()
        val source=object:YouTubeSource {override suspend fun resolve(videoId:String)=ResolvedVideo(videoId,"Fixture",2256,StreamSpec("https://example.invalid/not-used","video/mp4",emptyMap()),null,"synthetic")}
        val c=PlaybackCoordinator(source,store,player,this,ioDispatcher=StandardTestDispatcher(testScheduler),onlinePlayback={online})
        c.start();c.play();runCurrent();player.position=423539L
        return Triple(c,player,store)
    }
    @Test fun reopensOnlineFromActualSavedPositionWithoutManualPlayOrQueueAdvance()=runTest {
        val (c,p,s)=fixture();val generation=c.currentSnapshot().generation
        c.onError(generation,PlaybackFailure(PlaybackErrorKind.NETWORK,onlineRecovery=OnlineRecovery.CONTAINER))
        assertEquals(PlaybackStatus.BUFFERING,c.currentSnapshot().status);assertNull(c.currentSnapshot().error)
        assertEquals(423539L,s.state.positionMs);assertEquals("q1",s.state.currentItem!!.queueItemId)
        c.onFirstFrame(generation);c.onEnded(generation)
        assertEquals(PlaybackStatus.BUFFERING,c.currentSnapshot().status);assertEquals("q1",s.state.currentItem!!.queueItemId)
        advanceTimeBy(999);runCurrent();assertEquals(1,p.positions.size)
        advanceTimeBy(1);runCurrent();assertEquals(listOf(0L,423539L),p.positions)
        assertEquals(PlaybackIntent.PLAY,s.state.intent)
        c.onError(generation,PlaybackFailure(PlaybackErrorKind.NETWORK,onlineRecovery=OnlineRecovery.NETWORK))
        advanceTimeBy(1000);runCurrent();assertEquals(2,p.positions.size);c.close()
    }
    @Test fun parserRetriesAreBoundedUntilRealPlaybackProgress()=runTest {
        val (c,p,_)=fixture()
        for(wait in listOf(1000L,2000L)) {
            c.onError(c.currentSnapshot().generation,PlaybackFailure(PlaybackErrorKind.NETWORK,onlineRecovery=OnlineRecovery.CONTAINER))
            advanceTimeBy(wait);runCurrent();p.position=423539L
        }
        c.onError(c.currentSnapshot().generation,PlaybackFailure(PlaybackErrorKind.NETWORK,onlineRecovery=OnlineRecovery.CONTAINER))
        assertEquals(PlaybackStatus.ERROR,c.currentSnapshot().status);advanceTimeBy(60000);runCurrent();assertEquals(3,p.positions.size)
        c.play();runCurrent();p.position=423539L
        c.onError(c.currentSnapshot().generation,PlaybackFailure(PlaybackErrorKind.NETWORK,onlineRecovery=OnlineRecovery.CONTAINER));advanceTimeBy(1000);runCurrent()
        p.position=450000L
        c.onError(c.currentSnapshot().generation,PlaybackFailure(PlaybackErrorKind.NETWORK,onlineRecovery=OnlineRecovery.CONTAINER));advanceTimeBy(1000);runCurrent()
        assertEquals(6,p.positions.size);c.close()
    }
    @Test fun networkBackoffSurvivesRepeatedFailuresAndStopPauseNextAndCloseCancelIt()=runTest {
        val (c,p,_)=fixture()
        for(wait in listOf(1000L,2000L,4000L,8000L,16000L,30000L,30000L)) {
            p.position=423539L;c.onError(c.currentSnapshot().generation,PlaybackFailure(PlaybackErrorKind.NETWORK,onlineRecovery=OnlineRecovery.NETWORK))
            advanceTimeBy(wait);runCurrent()
        }
        assertEquals(8,p.positions.size);c.close()
        for(action in listOf("STOP","PAUSE","NEXT","CLOSE")) {
            val (next,player,store)=fixture()
            next.onError(next.currentSnapshot().generation,PlaybackFailure(PlaybackErrorKind.NETWORK,onlineRecovery=OnlineRecovery.NETWORK))
            when(action){"STOP"->next.stop();"PAUSE"->next.pauseResume();"NEXT"->next.next();else->next.close()}
            runCurrent();val before=player.positions.size;advanceTimeBy(60000);runCurrent();assertEquals(action,before,player.positions.size)
            if(action=="STOP")assertEquals(PlaybackIntent.STOP,store.state.intent)
            if(action=="PAUSE")assertEquals(PlaybackIntent.PAUSE,store.state.intent)
            if(action=="NEXT")assertEquals("q2",store.state.currentItem!!.queueItemId)
            next.close()
        }
    }
    @Test fun offlineDecoderUnavailableAndAuthenticationDoNotAutoRetry()=runTest {
        for(failure in listOf(PlaybackFailure(PlaybackErrorKind.DECODER),PlaybackFailure(PlaybackErrorKind.UNAVAILABLE),
            PlaybackFailure(PlaybackErrorKind.NETWORK,stopRequested=true,onlineRecovery=OnlineRecovery.NETWORK))) {
            val (c,p,_)=fixture();c.onError(c.currentSnapshot().generation,failure);advanceTimeBy(60000);runCurrent()
            assertEquals(PlaybackStatus.ERROR,c.currentSnapshot().status);assertEquals(1,p.positions.size);c.close()
        }
        val (c,p,_)=fixture(false);c.onError(c.currentSnapshot().generation,PlaybackFailure(PlaybackErrorKind.NETWORK,onlineRecovery=OnlineRecovery.NETWORK))
        advanceTimeBy(60000);runCurrent();assertEquals(1,p.positions.size);assertEquals(PlaybackStatus.ERROR,c.currentSnapshot().status);c.close()
    }
    @Test fun networkFailureWhileReopeningAlsoRetriesAndKeepsThePersistedPosition()=runTest {
        val store=Store();store.state=store.state.withPosition(123000);val player=Player();var calls=0
        val source=object:YouTubeSource {override suspend fun resolve(videoId:String):ResolvedVideo {
            if(++calls<=2)throw YouTubeSourceException(PlaybackFailure(PlaybackErrorKind.NETWORK))
            return ResolvedVideo(videoId,"Fixture",2256,StreamSpec("https://example.invalid/not-used","video/mp4",emptyMap()),null,"synthetic")
        }}
        val c=PlaybackCoordinator(source,store,player,this,ioDispatcher=StandardTestDispatcher(testScheduler),onlinePlayback={true})
        c.start();c.play();runCurrent();assertEquals(PlaybackStatus.BUFFERING,c.currentSnapshot().status)
        advanceTimeBy(1000);runCurrent();assertEquals(2,calls);assertTrue(player.positions.isEmpty())
        advanceTimeBy(2000);runCurrent();assertEquals(3,calls);assertEquals(listOf(123000L),player.positions)
        assertEquals("q1",store.state.currentItem!!.queueItemId);c.close()
    }

}

package dev.siyovush.parentcast.tv.playback

import dev.siyovush.parentcast.tv.queue.*
import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProgrammeCoordinatorTest {
    private val a=QueueItem("a","video000001")
    private val b=QueueItem("b","video000002")
    private val c=QueueItem("c","video000003")
    private fun TestScope.fixture(intent: PlaybackIntent=PlaybackIntent.PAUSE, existing: Store? = null): Triple<PlaybackCoordinator,Store,Player> {
        val store=existing ?: Store(QueueState(listOf(a,b,c),1,1200,intent));val player=Player()
        val coordinator=PlaybackCoordinator(object:YouTubeSource{override suspend fun resolve(videoId:String)=ResolvedVideo(videoId,"Видео",100,StreamSpec("https://media.invalid/video","video/mp4",emptyMap()),null,"test")},store,player,this,StandardTestDispatcher(testScheduler))
        coordinator.start();runCurrent();return Triple(coordinator,store,player)
    }
    @Test fun reorderAndUndoPreserveLivePositionAndNoPlayerCallsAcrossRestart()=runTest {
        val (coordinator,store,player)=fixture();val before=coordinator.currentSnapshot();val calls=player.calls
        assertEquals(BatchAddResult.APPLIED,coordinator.programmeCommand(ProgrammeCommand.ReorderLoose(listOf(c.videoId,b.videoId,a.videoId)),before.queueState.version,"reorder-key","fp"))
        val undo=store.state.undo!!
        player.position=4500;coordinator.savePosition()
        val saved=QueueSnapshotCodec.decode(QueueSnapshotCodec.encode(store.state));assertEquals(undo,saved.undo)
        assertEquals(BatchAddResult.APPLIED,coordinator.programmeCommand(ProgrammeCommand.Undo(undo.id),saved.version,"undo-key","undo-fp"))
        assertEquals(listOf(a,b,c),store.state.items);assertEquals(b,store.state.currentItem);assertEquals(4500L,store.state.positionMs)
        assertEquals(4500L,store.state.progress.getValue(b.videoId).positionMs);assertEquals(calls,player.calls);assertNull(store.state.undo)
        coordinator.close()
    }
    @Test fun staleOrWrongUndoCannotOverwriteOtherParentAndFailedWriteIsAtomic()=runTest {
        val (coordinator,store,player)=fixture();val original=store.state;val beforeMemory=coordinator.currentSnapshot().queueState
        store.fail=true
        assertEquals(BatchAddResult.STORAGE_ERROR,coordinator.programmeCommand(ProgrammeCommand.Favorite(a.videoId,true),original.version,"failure-key","fp"))
        assertEquals(original,store.state);assertEquals(beforeMemory,coordinator.currentSnapshot().queueState)
        store.fail=false
        assertEquals(BatchAddResult.APPLIED,coordinator.programmeCommand(ProgrammeCommand.Favorite(a.videoId,true),original.version,"first-key","fp"))
        val first=store.state
        assertEquals(BatchAddResult.APPLIED,coordinator.programmeCommand(ProgrammeCommand.Favorite(c.videoId,true),first.version,"other-key","other"))
        val other=store.state
        assertEquals(BatchAddResult.CONFLICT,coordinator.programmeCommand(ProgrammeCommand.Undo(first.undo!!.id),first.version,"undo-key","undo"))
        assertEquals("UNDO_UNAVAILABLE",assertThrows(ProgrammeConflict::class.java){coordinator.programmeCommand(ProgrammeCommand.Undo(first.undo!!.id),other.version,"undo-key-2","undo-2")}.code)
        assertEquals(other,store.state);coordinator.close()
    }
    @Test fun sameCommandReplaysAcrossRestartButChangedFingerprintIsRejected()=runTest {
        val (coordinator,store,_)=fixture()
        assertEquals(BatchAddResult.APPLIED,coordinator.programmeCommand(ProgrammeCommand.CreateGroup("Новый",listOf(a.videoId,c.videoId)),store.state.version,"create-key","create-fp"))
        val written=store.writes
        assertEquals(BatchAddResult.REPLAY,coordinator.programmeCommand(ProgrammeCommand.CreateGroup("Новый",listOf(a.videoId,c.videoId)),0,"create-key","create-fp"))
        assertEquals(BatchAddResult.KEY_REUSED,coordinator.programmeCommand(ProgrammeCommand.CreateGroup("Другой",listOf(a.videoId)),store.state.version,"create-key","other-fp"))
        assertEquals(written,store.writes);assertEquals(1,store.state.groups.size);assertNotNull(store.state.undo)
        coordinator.close()
        val restarted=fixture(existing=Store(QueueSnapshotCodec.decode(QueueSnapshotCodec.encode(store.state)))).first
        assertEquals(BatchAddResult.REPLAY,restarted.programmeCommand(ProgrammeCommand.CreateGroup("Новый",listOf(a.videoId,c.videoId)),0,"create-key","create-fp"))
        val snapshot=restarted.currentSnapshot().queueState
        assertEquals(BatchAddResult.APPLIED,restarted.programmeCommand(ProgrammeCommand.Undo(snapshot.undo!!.id),snapshot.version,"restart-undo","restart-undo"))
        assertTrue(restarted.currentSnapshot().queueState.groups.isEmpty());restarted.close()
    }
    @Test fun bookmarksSurviveStructureUndoAndNaturalEndTracksWatchedThenStops()=runTest {
        val (coordinator,store,player)=fixture(PlaybackIntent.PLAY)
        coordinator.programmeCommand(ProgrammeCommand.Favorite(a.videoId,true),store.state.version,"favorite-key","favorite")
        val undo=store.state.undo!!
        coordinator.programmeCommand(ProgrammeCommand.AddBookmark(b.videoId,"Пояснение",1000),store.state.version,"bookmark-key","bookmark")
        coordinator.programmeCommand(ProgrammeCommand.Undo(undo.id),store.state.version,"undo-key","undo")
        assertEquals(1,store.state.progress.getValue(b.videoId).bookmarks.size)
        coordinator.programmeCommand(ProgrammeCommand.StopAfter(true),store.state.version,"stop-key","stop")
        player.position=100_000;coordinator.onEnded(coordinator.currentSnapshot().generation)
        assertEquals(VideoWatchStatus.WATCHED,store.state.progress.getValue(b.videoId).status)
        assertEquals(100_000L,store.state.progress.getValue(b.videoId).positionMs)
        assertEquals(c,store.state.currentItem);assertEquals(PlaybackIntent.STOP,store.state.intent);assertFalse(store.state.stopAfterCurrent)
        assertTrue(player.stopped);coordinator.close()
    }
    @Test fun legacyReorderAlsoProvidesUndoWithoutRewindingPlayback()=runTest {
        val (coordinator,store,player)=fixture()
        val group=QueueGroup("g_"+"a".repeat(32),"Группа",listOf(a.videoId,b.videoId,c.videoId),QueueGroupSource.CUSTOM)
        coordinator.addBatch(group.videoIds,store.state.version,"batch-group","batch",group=group)
        val calls=player.calls
        coordinator.reorderGroups(group.videoIds.reversed(),group.id,store.state.version,"group-order","order")
        val undo=store.state.undo!!
        coordinator.programmeCommand(ProgrammeCommand.Undo(undo.id),store.state.version,"undo-order","undo")
        assertEquals(group.videoIds,store.state.groups.single().videoIds);assertEquals(calls,player.calls)
        coordinator.close()
    }
    @Test fun onlineAppendToMixedGroupIsAtomicAndRejectsUnreferencedUnknownIds()=runTest {
        val local=QueueItem("local","local_"+"a".repeat(32),true)
        val group=QueueGroup("g_"+"b".repeat(32),"Смешанный",listOf(local.videoId,a.videoId),QueueGroupSource.CUSTOM,favorite=true)
        val (coordinator,store,player)=fixture(existing=Store(QueueState(listOf(local,a,b),1,1200,PlaybackIntent.PAUSE,groups=listOf(group),activeGroupId=group.id)))
        val before=coordinator.currentSnapshot();val calls=player.calls
        val bad=group.copy(videoIds=group.videoIds+"video000099"+c.videoId)
        assertThrows(IllegalArgumentException::class.java){coordinator.addBatch(listOf(c.videoId),store.state.version,"mixed-bad","bad",group=bad)}
        assertEquals(before.queueState.items,store.state.items)
        assertEquals(BatchAddResult.APPLIED,coordinator.addBatch(listOf(c.videoId),store.state.version,"mixed-good","good",group=group.copy(videoIds=group.videoIds+c.videoId)))
        assertEquals(listOf(local.videoId,a.videoId,c.videoId),store.state.groups.single().videoIds)
        assertTrue(store.state.groups.single().favorite);assertEquals(before.queueState.currentItem,store.state.currentItem)
        assertEquals(before.queueState.activeGroupId,store.state.activeGroupId);assertEquals(calls,player.calls)
        coordinator.close()
    }
    @Test fun undoDoesNotRegrantDeletedChildGroupAndStopFlagDoesNotBlockExplicitNext()=runTest {
        val (coordinator,store,_)=fixture(PlaybackIntent.PLAY)
        coordinator.programmeCommand(ProgrammeCommand.CreateGroup("Дети",listOf(a.videoId)),store.state.version,"create-child","create-child")
        val group=store.state.groups.single()
        coordinator.programmeCommand(ProgrammeCommand.Child(true,listOf(group.id)),store.state.version,"grant-child","grant-child")
        coordinator.programmeCommand(ProgrammeCommand.Membership(listOf(a.videoId),group.id,null,"REMOVE"),store.state.version,"remove-membership","remove")
        assertTrue(store.state.childScreen.groupIds.isEmpty())
        coordinator.programmeCommand(ProgrammeCommand.Undo(store.state.undo!!.id),store.state.version,"restore-group","restore")
        assertEquals(group,store.state.groups.single());assertTrue(store.state.childScreen.groupIds.isEmpty())
        coordinator.programmeCommand(ProgrammeCommand.StopAfter(true),store.state.version,"stop-after-next","stop-after")
        coordinator.next();runCurrent()
        assertEquals(c,store.state.currentItem);assertEquals(PlaybackIntent.PLAY,store.state.intent);assertTrue(store.state.stopAfterCurrent)
        coordinator.close()
    }
    @Test fun explicitChildResumeClearsOutsideOverrideAndKeepsAllowedGroupPosition()=runTest {
        val group=QueueGroup("g_"+"c".repeat(32),"Для детей",listOf(a.videoId,c.videoId),QueueGroupSource.CUSTOM)
        val original=QueueState(listOf(a,b,c),0,1200,PlaybackIntent.PAUSE,groups=listOf(group),activeGroupId=group.id,
            playNext=NextVideo(b.videoId),childScreen=ChildScreen(true,listOf(group.id)))
        val (coordinator,store,_)=fixture(existing=Store(original))
        val snapshot=coordinator.currentSnapshot().queueState
        assertEquals(BatchAddResult.APPLIED,coordinator.selectQueued(a.queueItemId,snapshot.version,snapshot.positionMs,true,"child-resume-key","child-resume",group.id))
        runCurrent()
        assertNull(store.state.playNext);assertEquals(1200L,store.state.positionMs);assertEquals(group.id,store.state.activeGroupId)
        coordinator.next();runCurrent()
        assertEquals(c,store.state.currentItem);assertEquals(group.id,store.state.activeGroupId)
        coordinator.next();assertEquals(PlaybackIntent.STOP,store.state.intent);assertTrue(store.state.finished)
        coordinator.close()
    }
    private class Store(var state:QueueState):QueueStore{var fail=false;var writes=0;override fun read()=state;override fun write(state:QueueState){if(fail)throw IOException();this.state=state;writes++}}
    private class Player:PlaybackPlayer {
        var position=1200L;var calls=0;var stopped=false
        override fun setListener(listener:PlaybackPlayer.Listener){}
        override fun prepare(generation:Long,video:ResolvedVideo,positionMs:Long,playWhenReady:Boolean){calls++;position=positionMs;stopped=false}
        override fun play(){calls++;stopped=false};override fun pause(){calls++};override fun stop(){calls++;stopped=true}
        override fun currentPositionMs()=position;override fun durationMs()=100_000L;override fun isSeekable()=true;override fun release(){}
    }
}

package dev.siyovush.parentcast.tv.queue

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProgrammeStateTest {
    private val a=QueueItem("a","video000001")
    private val b=QueueItem("b","video000002")
    private val c=QueueItem("c","video000003")
    private val d=QueueItem("d","local_"+"1".repeat(32),true)
    private val group=QueueGroup("g_"+"a".repeat(32),"Группа",listOf(a.videoId,c.videoId),QueueGroupSource.CUSTOM)
    private fun state()=QueueState(listOf(a,b,c,d),1,1234,PlaybackIntent.PAUSE,groups=listOf(group))
    @Test fun looseReorderMovesOnlyLooseSlotsAndRetainsCurrentIdentityPosition() {
        val s=state();val result=s.applyProgramme(ProgrammeCommand.ReorderLoose(listOf(d.videoId,b.videoId)))
        assertEquals(listOf(a,d,c,b),result.items);assertEquals(b,result.currentItem);assertEquals(1234L,result.positionMs)
        assertEquals(s.groups,result.groups);assertEquals(s.intent,result.intent)
        assertThrows(IllegalArgumentException::class.java){s.applyProgramme(ProgrammeCommand.ReorderLoose(listOf(c.videoId,b.videoId)))}
    }
    @Test fun copyMoveAndRemoveMembershipPreserveMediaAndLocalIds() {
        val s=state().applyProgramme(ProgrammeCommand.CreateGroup("Свои",listOf(d.videoId)))
        val target=s.groups.last()
        val copied=s.applyProgramme(ProgrammeCommand.Membership(listOf(a.videoId),group.id,target.id,"COPY"))
        assertEquals(listOf(d.videoId,a.videoId),copied.groups.last().videoIds);assertEquals(s.items,copied.items)
        val moved=copied.applyProgramme(ProgrammeCommand.Membership(listOf(c.videoId),group.id,target.id,"MOVE"))
        assertEquals(listOf(a.videoId),moved.groups.first().videoIds)
        val loose=moved.applyProgramme(ProgrammeCommand.Membership(listOf(c.videoId),target.id,null,"REMOVE"))
        assertTrue(c.videoId in loose.looseVideoIds());assertEquals(s.items,loose.items)
    }
    @Test fun removingCurrentRequiresStopAndNeverForgetsHistory() {
        val s=state().copy(progress=mapOf(b.videoId to VideoProgress(1234,10000,VideoWatchStatus.IN_PROGRESS,10)))
        assertEquals("CURRENT_VIDEO_IN_USE",assertThrows(ProgrammeConflict::class.java){s.applyProgramme(ProgrammeCommand.RemoveVideos(listOf(b.videoId)))}.code)
        val removed=s.stop().applyProgramme(ProgrammeCommand.RemoveVideos(listOf(b.videoId)))
        assertEquals(listOf(a,c,d),removed.items);assertEquals(s.progress,removed.progress);assertEquals(PlaybackIntent.STOP,removed.intent)
    }
    @Test fun groupCoverCannotReferenceOtherVideoOrBothCoverKinds() {
        val s=state()
        assertThrows(IllegalArgumentException::class.java){s.applyProgramme(ProgrammeCommand.UpdateGroup(group.id,changeCover=true,coverVideoId=b.videoId))}
        assertThrows(IllegalArgumentException::class.java){s.applyProgramme(ProgrammeCommand.UpdateGroup(group.id,changeCover=true,coverVideoId=a.videoId,coverImageId="a".repeat(64)))}
        val covered=s.applyProgramme(ProgrammeCommand.UpdateGroup(group.id,favorite=true,changeCover=true,coverVideoId=c.videoId))
        assertTrue(covered.groups.first().favorite)
        val detached=covered.applyProgramme(ProgrammeCommand.Membership(listOf(c.videoId),group.id,null,"REMOVE"))
        assertNull(detached.groups.first().coverVideoId)
    }
    @Test fun playNextIsOneShotWithPersistentReturnToOriginalGroup() {
        val s=state().copy(currentIndex=0,activeGroupId=group.id,intent=PlaybackIntent.PLAY)
            .applyProgramme(ProgrammeCommand.PlayNext(d.videoId))
        val inserted=s.next();assertEquals(d,inserted.currentItem);assertNull(inserted.activeGroupId)
        val restored=QueueSnapshotCodec.decode(QueueSnapshotCodec.encode(inserted)).next()
        assertEquals(c,restored.currentItem);assertEquals(group.id,restored.activeGroupId);assertNull(restored.playNext)
        assertTrue(restored.next().finished)
    }
    @Test fun oneShotDoesNotFallIntoGlobalQueueWhenReturnGroupWasRemoved() {
        val inserted=state().copy(currentIndex=0,activeGroupId=group.id,intent=PlaybackIntent.PLAY)
            .applyProgramme(ProgrammeCommand.PlayNext(d.videoId)).next()
        val changed=inserted.applyProgramme(ProgrammeCommand.Membership(group.videoIds,group.id,null,"REMOVE"))
        val ended=changed.next()
        assertEquals(d,ended.currentItem);assertEquals(PlaybackIntent.STOP,ended.intent);assertTrue(ended.finished);assertNull(ended.playNext)
    }
    @Test fun undoRefusesToRemoveAnActiveGroupEvenWhenCurrentFileWouldRemain() {
        val before=state().copy(groups=emptyList())
        val current=state().copy(currentIndex=0,activeGroupId=group.id,intent=PlaybackIntent.PLAY)
        val undo=ProgrammeUndo("u_"+"1".repeat(32),"Создание",before.structure(),current.structure().fingerprint())
        assertEquals("UNDO_CURRENT_VIDEO_IN_USE",assertThrows(ProgrammeConflict::class.java){current.copy(undo=undo).applyProgramme(ProgrammeCommand.Undo(undo.id))}.code)
    }
    @Test fun childAllowlistIsExplicitAndRemovingLastMemberRevokesGrant() {
        val s=state().applyProgramme(ProgrammeCommand.Child(true,listOf(group.id)))
        val created=s.applyProgramme(ProgrammeCommand.CreateGroup("Новая",listOf(d.videoId)))
        assertEquals(listOf(group.id),created.childScreen.groupIds)
        val removed=created.applyProgramme(ProgrammeCommand.Membership(group.videoIds,group.id,null,"REMOVE"))
        assertEquals(emptyList<String>(),removed.childScreen.groupIds);assertTrue(removed.childScreen.enabled)
        assertThrows(IllegalArgumentException::class.java){s.applyProgramme(ProgrammeCommand.Child(true,listOf("g_"+"f".repeat(32))))}
    }
    @Test fun snapshotV4RoundTripsAllProgrammeDataAndReadsV1To3() {
        var s=state().applyProgramme(ProgrammeCommand.CreateGroup("Свои",listOf(d.videoId)))
            .applyProgramme(ProgrammeCommand.AddBookmark(b.videoId,"Начало урока",1234))
            .applyProgramme(ProgrammeCommand.Favorite(b.videoId,true))
            .applyProgramme(ProgrammeCommand.Child(true,listOf(group.id)))
            .applyProgramme(ProgrammeCommand.PlayNext(d.videoId)).copy(stopAfterCurrent=true)
        s=s.copy(undo=ProgrammeUndo("u_"+"1".repeat(32),"Порядок",state().structure(),s.structure().fingerprint()))
        assertEquals(s,QueueSnapshotCodec.decode(QueueSnapshotCodec.encode(s)))
        for(version in 1..3) {
            val json=JSONObject(QueueSnapshotCodec.encode(state())).put("version",version);json.remove("programme")
            val old=QueueSnapshotCodec.decode(json.toString())
            assertEquals(state().items,old.items);assertTrue(old.progress.isEmpty());assertFalse(old.childScreen.enabled);assertNull(old.playNext)
        }
    }
}

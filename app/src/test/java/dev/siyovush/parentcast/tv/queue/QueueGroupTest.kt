package dev.siyovush.parentcast.tv.queue

import org.junit.Assert.*
import org.junit.Test

class QueueGroupTest {
    private val a="video000001";private val b="video000002";private val c="vk:-1_2"
    private fun group(ids: List<String>)=QueueGroup.fixed("test","Истории",ids,QueueGroupSource.COLLECTION)
    private fun state()=QueueState(items=listOf(QueueItem("a",a,title="First"),QueueItem("b",b),QueueItem("c",c)),currentIndex=2,
        intent=PlaybackIntent.PLAY,groups=listOf(group(listOf(c,a)))) .let { it.copy(activeGroupId=it.groups[0].id) }
    @Test fun selectedPlaylistUsesOwnOrderAndStopsBeforeUnrelatedVideos() {
        val s=state();assertEquals(listOf(c,a),s.remainingItems().map { it.videoId })
        val next=s.next();assertEquals(a,next.currentItem!!.videoId)
        val end=next.next();assertTrue(end.finished);assertEquals(PlaybackIntent.STOP,end.intent)
        assertEquals(a,end.currentItem!!.videoId);assertEquals(c,end.play().currentItem!!.videoId)
    }
    @Test fun snapshotPreservesGroupsSharedReferencesNamesAndActiveScope() {
        val s=state().let { it.copy(groups=it.groups+QueueGroup.fixed("other","Вечер",listOf(a,b),QueueGroupSource.YOUTUBE_PLAYLIST)) }
        assertEquals(s,QueueSnapshotCodec.decode(QueueSnapshotCodec.encode(s)))
        assertEquals(3,s.items.size);assertEquals(4,s.groups.sumOf { it.videoIds.size })
    }
    @Test fun legacyV2IsReadableWithoutInventingPlaylistIdentity() {
        val old="""{"version":2,"items":[{"queueItemId":"a","videoId":"video000001","local":false}],"currentIndex":0,"positionMs":1234,"intent":"PAUSE","finished":false,"queueVersion":12}"""
        val s=QueueSnapshotCodec.decode(old);assertEquals(1234L,s.positionMs);assertEquals(12L,s.version)
        assertTrue(s.groups.isEmpty());assertNull(s.activeGroupId)
    }
    @Test fun groupIdsAreStableAndTitlesRemainPlainUntrustedText() {
        assertEquals(group(listOf(a,b)),group(listOf(a,b,a)))
        assertEquals("<script>title</script> x",cleanVideoTitle("<script>title</script>\nx"))
        assertThrows(IllegalArgumentException::class.java) { group(listOf("https://evil.test")) }
        assertThrows(IllegalArgumentException::class.java) { state().copy(groups=listOf(group(listOf("video000099")))) }
        assertThrows(IllegalArgumentException::class.java) { state().copy(activeGroupId="missing") }
    }
    @Test fun physicalRemovalPrunesOnlyItsReferencesAndKeepsOtherGroups() {
        val s=QueueState(items=listOf(QueueItem("a",a),QueueItem("b",b),QueueItem("c",c)),currentIndex=0,
            groups=listOf(group(listOf(a,b)),QueueGroup.fixed("other","Другое",listOf(b,c),QueueGroupSource.COLLECTION)))
        val changed=s.deletePending("b");assertEquals(listOf(listOf(a),listOf(c)),changed.groups.map { it.videoIds })
    }
}

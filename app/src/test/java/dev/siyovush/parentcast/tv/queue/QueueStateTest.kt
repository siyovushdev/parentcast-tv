package dev.siyovush.parentcast.tv.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueStateTest {
    @Test
    fun addAppendsWithoutStartingPlayback() {
        val state = QueueState.Empty
            .add(item(1))
            .add(item(2))
            .add(item(3))

        assertEquals(listOf("video000001", "video000002", "video000003"), state.items.map { it.videoId })
        assertEquals(0, state.currentIndex)
        assertEquals(PlaybackIntent.STOP, state.intent)
        assertEquals(listOf("q2", "q3"), state.pendingItems.map { it.queueItemId })
    }

    @Test
    fun duplicateVideoKeepsOriginalQueueIdentity() {
        val state = QueueState.Empty
            .add(QueueItem("first", "video000001"))
            .add(QueueItem("second", "video000001"))

        assertEquals(1, state.items.size)
        assertEquals("first", state.items.single().queueItemId)
    }

    @Test
    fun playNowReusesExistingEntryIncludingHistoryAndFinishedQueue() {
        val initial = QueueState.Empty.add(item(1)).add(item(2)).next().withPosition(900)
        for (state in listOf(initial, initial.next())) {
            val selected = state.playNow(QueueItem("new-id", "video000001"))
            assertEquals(state.items, selected.items)
            assertEquals(0, selected.currentIndex)
            assertEquals(0L, selected.positionMs)
            assertEquals(PlaybackIntent.PLAY, selected.intent)
            assertFalse(selected.finished)
        }
    }

    @Test
    fun nextFollowsOrderAndEndsWithoutAutoplay() {
        var state = QueueState.Empty
            .add(item(1))
            .add(item(2))
            .add(item(3))
            .play()

        state = state.next()
        assertEquals("video000002", state.currentItem?.videoId)
        assertEquals(PlaybackIntent.PLAY, state.intent)
        state = state.next()
        assertEquals("video000003", state.currentItem?.videoId)
        assertEquals(PlaybackIntent.PLAY, state.intent)
        state = state.next()

        assertEquals("video000003", state.currentItem?.videoId)
        assertEquals(PlaybackIntent.STOP, state.intent)
        assertTrue(state.finished)
        assertEquals(0, state.positionMs)
    }

    @Test
    fun stopAndPausePreservePositionAndIntent() {
        var state = QueueState.Empty.add(item(1)).play().withPosition(12_345).togglePause()
        assertEquals(PlaybackIntent.PAUSE, state.intent)
        assertEquals(12_345, state.positionMs)

        state = state.togglePause().stop()
        assertEquals(PlaybackIntent.STOP, state.intent)
        assertEquals(12_345, state.positionMs)
        assertEquals("video000001", state.currentItem?.videoId)
    }

    @Test
    fun deleteRemovesOnlyPendingItem() {
        val original = QueueState.Empty.add(item(1)).add(item(2)).add(item(3)).play()
        val afterCurrentDelete = original.deletePending("q1")
        assertEquals(original, afterCurrentDelete)

        val updated = original.deletePending("q2")
        assertEquals(listOf("q1", "q3"), updated.items.map { it.queueItemId })
        assertEquals("video000001", updated.currentItem?.videoId)
        assertEquals(PlaybackIntent.PLAY, updated.intent)
    }

    @Test
    fun playAfterFinishedAdvancesToNewlyAddedItem() {
        var state = QueueState.Empty.add(item(1)).play().next()
        assertTrue(state.finished)
        state = state.add(item(2)).play()

        assertEquals("video000002", state.currentItem?.videoId)
        assertEquals(PlaybackIntent.PLAY, state.intent)
        assertFalse(state.finished)
    }

    private fun item(number: Int) = QueueItem("q$number", "video00000$number")
}

package dev.siyovush.parentcast.tv.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueStateM1Test {
    @Test
    fun playNowInterruptsWithoutReturningInterruptedItem() {
        var state = QueueState.Empty.add(item(1)).add(item(2)).add(item(3)).play()

        state = state.playNow(item(4))

        assertEquals("video000004", state.currentItem?.videoId)
        assertEquals(1, state.currentIndex)
        assertEquals(listOf("q1", "q4", "q2", "q3"), state.items.map { it.queueItemId })
        assertEquals(PlaybackIntent.PLAY, state.intent)
        assertEquals(0, state.positionMs)
    }

    @Test
    fun playNowAfterFinishedStartsNewItem() {
        val finished = QueueState.Empty.add(item(1)).play().next()
        val updated = finished.playNow(item(2))

        assertEquals("video000002", updated.currentItem?.videoId)
        assertEquals(PlaybackIntent.PLAY, updated.intent)
        assertFalse(updated.finished)
    }

    @Test
    fun movePendingChangesOnlyPendingOrder() {
        val state = QueueState.Empty.add(item(1)).add(item(2)).add(item(3)).add(item(4)).play()

        val movedUp = state.movePending("q3", moveUp = true)
        assertEquals(listOf("q1", "q3", "q2", "q4"), movedUp.items.map { it.queueItemId })
        assertEquals("video000001", movedUp.currentItem?.videoId)

        val movedDown = movedUp.movePending("q3", moveUp = false)
        assertEquals(listOf("q1", "q2", "q3", "q4"), movedDown.items.map { it.queueItemId })
        assertEquals(movedUp.movePending("q1", moveUp = true), movedUp)
    }

    @Test
    fun clearPendingKeepsCurrentItem() {
        val state = QueueState.Empty.add(item(1)).add(item(2)).add(item(3)).play()
        val updated = state.clearPending()

        assertEquals(listOf("q1"), updated.items.map { it.queueItemId })
        assertEquals("video000001", updated.currentItem?.videoId)
        assertEquals(PlaybackIntent.PLAY, updated.intent)
    }

    private fun item(number: Int) = QueueItem("q$number", "video00000$number")
}

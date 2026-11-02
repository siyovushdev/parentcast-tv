package dev.siyovush.parentcast.tv.queue

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicQueueStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writeAndReadRestoreQueuePositionAndIntent() {
        val file = temporaryFolder.newFile("queue-state.json")
        val store = AtomicQueueStore(file)
        val expected = QueueState(
            items = listOf(
                QueueItem("q1", "video000001"),
                QueueItem("q2", "video000002"),
                QueueItem("q3", "video000001"),
            ),
            currentIndex = 1,
            positionMs = 98_765,
            intent = PlaybackIntent.PAUSE,
        )

        store.write(expected)

        assertEquals(expected, store.read())
        assertEquals(
            listOf("queue-state.json"),
            temporaryFolder.root.listFiles().orEmpty().map { it.name },
        )
    }

    @Test
    fun snapshotStoresIdsRatherThanMediaUrls() {
        val file = temporaryFolder.newFile("queue-state.json")
        val store = AtomicQueueStore(file)
        store.write(QueueState.Empty.add(QueueItem("q1", "video000001")).play())

        val serialized = file.readText()
        assertTrue(serialized.contains("queueItemId"))
        assertTrue(serialized.contains("video000001"))
        assertFalse(serialized.contains("googlevideo"))
        assertFalse(serialized.contains("https://"))
    }

    @Test
    fun missingFileRestoresEmptyQueue() {
        val file = File(temporaryFolder.root, "missing.json")
        assertEquals(QueueState.Empty, AtomicQueueStore(file).read())
    }

    @Test
    fun corruptSnapshotIsNotAccepted() {
        val file = temporaryFolder.newFile("queue-state.json")
        file.writeText("not-json")
        assertThrows(JSONException::class.java) {
            AtomicQueueStore(file).read()
        }
    }
}

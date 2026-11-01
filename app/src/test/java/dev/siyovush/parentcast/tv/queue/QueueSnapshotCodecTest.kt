package dev.siyovush.parentcast.tv.queue

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueSnapshotCodecTest {
    @Test
    fun queueVersionRoundTrips() {
        val expected = QueueState(
            items = listOf(QueueItem("q1", "video000001")),
            currentIndex = 0,
            positionMs = 1234,
            intent = PlaybackIntent.PAUSE,
            version = 42,
        )

        assertEquals(expected, QueueSnapshotCodec.decode(QueueSnapshotCodec.encode(expected)))
    }

    @Test
    fun oldSnapshotWithoutVersionDefaultsToZero() {
        val serialized = """
            {"version":1,"items":[],"currentIndex":-1,"positionMs":0,"intent":"STOP","finished":false}
        """.trimIndent()

        assertEquals(0, QueueSnapshotCodec.decode(serialized).version)
    }
}

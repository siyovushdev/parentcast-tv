package dev.siyovush.parentcast.tv.streaming

import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class BufferedCacheSinkTest {
    private class Sink : DataSink {
        val bytes = ByteArrayOutputStream()
        var closed = false
        var writes = 0
        var failWrite = false
        var failClose = false
        override fun open(spec: DataSpec) { closed = false }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            writes++
            if (failWrite) { bytes.write(buffer, offset, 1); throw IOException("write failed") }
            bytes.write(buffer, offset, length)
        }
        override fun close() { closed = true; if (failClose) throw IOException("close failed") }
    }
    private fun spec() = DataSpec.Builder().setUri("https://example.invalid/media").build()

    @Test fun smallPacketsPreserveOrderAndFlushTailThroughTheBudget() {
        val target = Sink(); val charged = mutableListOf<Int>()
        val sink = BufferedCacheSink(target, { n, write -> charged += n; write() }, 16)
        val expected = ByteArray(39) { it.toByte() }
        sink.open(spec())
        for (at in expected.indices step 3) sink.write(expected, at, minOf(3, expected.size - at))
        assertEquals(listOf(16, 16), charged)
        sink.close()
        assertEquals(listOf(16, 16, 7), charged)
        assertArrayEquals(expected, target.bytes.toByteArray())
        assertTrue(target.closed)
        sink.close(); assertEquals(3, target.writes)
    }

    @Test fun finalFlushChecksQuotaBeforeWritingAndAlwaysClosesTheDelegate() {
        val target = Sink(); var checks = 0
        val sink = BufferedCacheSink(target, { _, _ -> checks++; error("NO_SPACE") }, 16)
        sink.open(spec()); sink.write(byteArrayOf(1, 2, 3), 0, 3)
        assertEquals("NO_SPACE", assertThrows(IllegalStateException::class.java) { sink.close() }.message)
        assertEquals(1, checks); assertEquals(0, target.writes); assertTrue(target.closed)
    }

    @Test fun aPartlyFailedPhysicalWriteIsNeverReplayedOnClose() {
        val target = Sink().apply { failWrite = true; failClose = true }
        val sink = BufferedCacheSink(target, { _, write -> write() }, 4)
        sink.open(spec())
        val error = assertThrows(IOException::class.java) { sink.write(byteArrayOf(1, 2, 3, 4), 0, 4) }
        sink.close()
        assertEquals(1, target.writes); assertEquals(1, target.bytes.size())
        assertEquals("close failed", error.suppressed.single().message)
        assertTrue(target.closed)
    }
}

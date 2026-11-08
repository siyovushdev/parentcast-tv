package dev.siyovush.parentcast.tv.prepared

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException

class DownloadBlocksTest {
    private fun packets(data: ByteArray, failAtEnd: Boolean = false) = object : ByteArrayInputStream(data) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (available() == 0 && failAtEnd) throw IOException("NETWORK_DROP")
            return super.read(b, off, minOf(len, 8192))
        }
    }

    @Test fun smallNetworkPacketsUseBoundedStorageWritesAndKeepExactBytes() {
        val data = ByteArray(1024 * 1024 + 17) { (it % 251).toByte() }
        val sizes = mutableListOf<Int>()
        val output = ByteArrayOutputStream()
        var cancellationChecks = 0
        copyDownloadBlocks(packets(data), { cancellationChecks++ }) { b, n -> sizes += n; output.write(b, 0, n) }
        assertArrayEquals(data, output.toByteArray())
        assertEquals(List(8) { 128 * 1024 } + 17, sizes)
        assertTrue(cancellationChecks >= 129) // Cancellation remains responsive between network packets.
    }

    @Test fun networkDropKeepsLastIncompleteBlockForRangeResume() {
        val data = ByteArray(128 * 1024 + 231) { 42 }
        val output = ByteArrayOutputStream()
        val error = assertThrows(IOException::class.java) {
            copyDownloadBlocks(packets(data, true), {}) { b, n -> output.write(b, 0, n) }
        }
        assertEquals("NETWORK_DROP", error.message)
        assertArrayEquals(data, output.toByteArray())
    }

    @Test fun cancellationDropsOnlyUnwrittenBufferAndDoesNotWriteAfterCancel() {
        var cancelled = false
        val source = object : ByteArrayInputStream(ByteArray(256 * 1024)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, minOf(len, 8192))
                if (pos > 128 * 1024) cancelled = true
                return n
            }
        }
        val writes = mutableListOf<Int>()
        assertThrows(CancellationException::class.java) {
            copyDownloadBlocks(source, { if (cancelled) throw CancellationException() }) { _, n -> writes += n }
        }
        assertEquals(listOf(128 * 1024), writes)
    }

    @Test fun failedQuotaOrStorageWriteIsNotReplayed() {
        for (reason in listOf("NO_SPACE", "STORAGE_UNAVAILABLE")) {
            var attempts = 0
            val error = assertThrows(IllegalStateException::class.java) {
                copyDownloadBlocks(packets(ByteArray(256 * 1024)), {}) { _, _ -> attempts++; error(reason) }
            }
            assertEquals(reason, error.message)
            assertEquals(1, attempts)
        }
    }

    @Test fun quotaFailureWhenFlushingBrokenNetworkRemainsAStorageFailure() {
        val error = assertThrows(IllegalStateException::class.java) {
            copyDownloadBlocks(packets(ByteArray(17), true), {}) { _, _ -> error("NO_SPACE") }
        }
        assertEquals("NO_SPACE", error.message)
    }
}

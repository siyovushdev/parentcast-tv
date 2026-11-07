package dev.siyovush.parentcast.tv.streaming

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec

/** Charge/check storage at bounded physical writes, rather than at every network packet. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class BufferedCacheSink(
    private val delegate: DataSink,
    private val writeChecked: (Int, () -> Unit) -> Unit,
    capacity: Int = 256 * 1024,
) : DataSink {
    private val buffer = ByteArray(capacity.also { require(it > 0) })
    private var count = 0
    private var opened = false
    private var writeFailure: Throwable? = null

    override fun open(spec: DataSpec) {
        check(!opened)
        count = 0
        writeFailure = null
        // close() must release the delegate even if its open partially fails.
        opened = true
        try { delegate.open(spec) } catch (error: Throwable) {
            writeFailure = error
            throw error
        }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(opened)
        check(writeFailure == null)
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        var at = offset
        var remaining = length
        while (remaining > 0) {
            val size = minOf(remaining, buffer.size - count)
            bytes.copyInto(buffer, count, at, at + size)
            count += size
            at += size
            remaining -= size
            if (count == buffer.size) flush()
        }
    }

    private fun flush() {
        if (count == 0) return
        val size = count
        // A failed physical write may have written a prefix. Never replay it on close.
        count = 0
        try { writeChecked(size) { delegate.write(buffer, 0, size) } } catch (error: Throwable) {
            writeFailure = error
            throw error
        }
    }

    override fun close() {
        if (!opened) return
        var failure: Throwable? = writeFailure
        try {
            if (failure == null) flush()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            count = 0
            opened = false
            try { delegate.close() } catch (error: Throwable) {
                if (failure != null) failure.addSuppressed(error) else throw error
            }
        }
    }
}

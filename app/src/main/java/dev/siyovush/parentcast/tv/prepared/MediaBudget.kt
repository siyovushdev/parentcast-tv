package dev.siyovush.parentcast.tv.prepared

import java.io.File
import java.io.FileOutputStream

/** All media writers share this gate, including browser/USB imports and YouTube. */
internal class MediaBudget(private val root: File, private val limit: Long = LIBRARY_LIMIT) {
    @Synchronized fun write(output: FileOutputStream, bytes: ByteArray, count: Int) {
        val used = root.listFiles().orEmpty().filter { it.name.endsWith(".mp4") || it.name.endsWith(".part") }.sumOf { it.length() }
        check(used + count <= limit && root.usableSpace - count >= FREE_RESERVE) { "NO_SPACE" }
        try { output.write(bytes, 0, count) } catch(error:java.io.IOException) { throw MediaStorageFailure(error) }
    }
}

internal class MediaStorageFailure(cause: Throwable) : Exception("STORAGE_ERROR",cause)

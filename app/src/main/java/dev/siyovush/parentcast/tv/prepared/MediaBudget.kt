package dev.siyovush.parentcast.tv.prepared

import java.io.File
import java.io.FileOutputStream

/** All media writers share this gate, including browser/USB imports and YouTube. */
internal class MediaBudget(private val root: File, private val limit: Long = LIBRARY_LIMIT, private val storage: PreparedStorage? = null) {
    @Synchronized fun write(output: FileOutputStream, bytes: ByteArray, count: Int, target:File? = null) {
        val destination=target?.parentFile ?: root
        val volume=storage?.volumes()?.firstOrNull { it.directory?.canonicalFile==destination.canonicalFile }
        if(storage!=null) {
            if(volume==null)throw StorageUnavailable("STORAGE_UNAVAILABLE")
            storage.root(volume.id,true)
        }
        val maximum=if(volume!=null && volume.id!=INTERNAL_STORAGE)storage!!.limit(volume.id) else limit
        val used = destination.listFiles().orEmpty().filter(PreparedStorage::isMediaFile).sumOf { it.length() }
        check(used + count <= maximum && destination.usableSpace - count >= FREE_RESERVE) { "NO_SPACE" }
        try { output.write(bytes, 0, count) } catch(error:java.io.IOException) { throw MediaStorageFailure(error) }
    }
}

internal class MediaStorageFailure(cause: Throwable) : Exception("STORAGE_ERROR",cause)

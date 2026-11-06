package dev.siyovush.parentcast.tv.prepared

import java.io.File
import java.io.FileOutputStream

/** All media writers share this gate, including browser/USB imports and YouTube. */
internal class MediaBudget(private val root: File, private val limit: Long = LIBRARY_LIMIT, private val storage: PreparedStorage? = null) {
    private data class Usage(var bytes:Long,var scannedAt:Long)
    private val usages=mutableMapOf<String,Usage>()
    private fun usage(destination:File,force:Boolean=false):Usage {
        val key=destination.canonicalPath;val now=System.nanoTime();val old=usages[key]
        if(old!=null && !force && now-old.scannedAt<250_000_000L)return old
        return Usage(PreparedStorage.mediaBytes(destination),now).also { usages[key]=it }
    }
    private fun writeCharged(destination:File,maximum:Long,count:Int,write:()->Unit) {
        var used=usage(destination)
        if(used.bytes+count>maximum)used=usage(destination,true)
        check(used.bytes+count<=maximum && destination.usableSpace-count>=FREE_RESERVE) { "NO_SPACE" }
        try { write();used.bytes+=count }catch(error:java.io.IOException){usages.remove(destination.canonicalPath);throw MediaStorageFailure(error)}
    }
    @Synchronized fun write(output: FileOutputStream, bytes: ByteArray, count: Int, target:File? = null) {
        val destination=target?.parentFile ?: root
        val volume=storage?.volumes()?.firstOrNull { it.directory?.canonicalFile==destination.canonicalFile }
        if(storage!=null) {
            if(volume==null)throw StorageUnavailable("STORAGE_UNAVAILABLE")
            storage.root(volume.id,true)
        }
        val maximum=if(volume!=null && volume.id!=INTERNAL_STORAGE)storage!!.limit(volume.id) else limit
        writeCharged(destination,maximum,count){output.write(bytes,0,count)}
    }
    @Synchronized fun writeCache(storageId:String,count:Int,write:()->Unit) {
        val volumes=checkNotNull(storage)
        val destination=volumes.root(storageId,true)
        val maximum=if(storageId==INTERNAL_STORAGE)limit else volumes.limit(storageId)
        writeCharged(destination,maximum,count,write)
    }
}

internal class MediaStorageFailure(cause: Throwable) : Exception("STORAGE_ERROR",cause)

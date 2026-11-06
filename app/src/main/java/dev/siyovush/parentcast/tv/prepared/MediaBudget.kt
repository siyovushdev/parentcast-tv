package dev.siyovush.parentcast.tv.prepared

import java.io.File
import java.io.FileOutputStream

/** All media writers share this gate, including browser/USB imports and YouTube. */
internal class MediaBudget(private val root: File, private val limit: Long = LIBRARY_LIMIT, private val storage: PreparedStorage? = null,
    private val nowNs:()->Long=System::nanoTime,
    private val scanBytes:(File)->Long=PreparedStorage::mediaBytes,
) {
    private data class Usage(var bytes:Long,var scannedAt:Long)
    private val usages=mutableMapOf<String,Usage>()
    private fun usage(destination:File,force:Boolean=false):Usage {
        val key=destination.canonicalPath;val now=nowNs();val old=usages[key]
        if(old!=null && !force && now-old.scannedAt<5_000_000_000L)return old
        val bytes=try {scanBytes(destination)}catch(error:Exception){throw MediaStorageFailure(error)}
        // Start the interval AFTER the scan: an SD scan itself can take seconds.
        return Usage(bytes,nowNs()).also { usages[key]=it }
    }
    /** All app media writes increment this ledger. Deletions may overcount until refresh;
     * a would-exceed-quota write always forces a fresh scan before admission. */
    @Synchronized fun usedBytes(destination:File=root):Long=usage(destination).bytes
    private fun writeCharged(destination:File,maximum:Long,count:Int,write:()->Unit) {
        var used=usage(destination)
        if(used.bytes+count>maximum)used=usage(destination,true)
        check(used.bytes+count<=maximum && destination.usableSpace-count>=FREE_RESERVE) { "NO_SPACE" }
        try { write();used.bytes+=count }catch(error:Throwable){
            usages.remove(destination.canonicalPath)
            if(error is java.io.IOException)throw MediaStorageFailure(error) else throw error
        }
    }
    @Synchronized fun write(output: FileOutputStream, bytes: ByteArray, count: Int, target:File? = null) {
        val destination=target?.parentFile ?: root
        val volume=try {storage?.writableVolume(destination)}catch(error:Exception){usages.clear();throw error}
        val maximum=if(volume!=null && volume.id!=INTERNAL_STORAGE)storage!!.limit(volume) else limit
        writeCharged(destination,maximum,count){output.write(bytes,0,count)}
    }
    @Synchronized fun writeCache(storageId:String,count:Int,write:()->Unit) {
        val volumes=checkNotNull(storage)
        val observed=try {volumes.writableVolume(storageId)}catch(error:Exception){usages.clear();throw error}
        val destination=checkNotNull(observed.directory)
        val maximum=if(storageId==INTERNAL_STORAGE)limit else volumes.limit(observed)
        writeCharged(destination,maximum,count,write)
    }
}

internal class MediaStorageFailure(cause: Throwable) : Exception("STORAGE_ERROR",cause)

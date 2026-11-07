package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.prepared.MEDIA_ID
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** Small derived images only. Jobs never use the player, queue writer or main dispatcher. */
internal class ThumbnailApi(
    private val directory: File,
    scope: CoroutineScope,
    private val generate: suspend (String) -> ByteArray?,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 32L * 1024 * 1024,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val jobs = mutableSetOf<String>()
    private val failures = linkedMapOf<String, Long>()
    private val worker = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + dispatcher)
    private val slot = kotlinx.coroutines.sync.Semaphore(1)
    init { directory.mkdirs() }

    @Synchronized fun read(id: String, allowed: Boolean): ApiResponse {
        if (!MEDIA_ID.matches(id)) return response(400, "BAD_MEDIA_ID")
        if (!allowed) return response(404, "THUMBNAIL_NOT_FOUND")
        val file = File(directory, "$id.jpg")
        if (file.isFile && file.length() in 4..MAX_IMAGE_BYTES.toLong()) {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes != null && jpeg(bytes)) {
                file.setLastModified(now())
                return ApiResponse(200, JSONObject().put("image", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)).toString())
            }
        }
        if (id in jobs) return response(202, "THUMBNAIL_PENDING")
        failures[id]?.let { if (now() - it < 60_000) return response(404, "THUMBNAIL_UNAVAILABLE") }
        if (jobs.size >= 16) return response(202, "THUMBNAIL_PENDING")
        jobs += id
        worker.launch {
            try {
                slot.acquire()
                try {
                    val bytes = withTimeoutOrNull(25_000) { generate(id) }
                    if (bytes != null && bytes.size in 4..MAX_IMAGE_BYTES && jpeg(bytes)) {
                        val temp = File(directory, "$id.tmp")
                        temp.writeBytes(bytes)
                        check(temp.renameTo(file))
                        synchronized(this@ThumbnailApi) {
                            failures.remove(id)
                            prune()
                        }
                    } else synchronized(this@ThumbnailApi) { failures[id] = now() }
                } finally { slot.release() }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { synchronized(this@ThumbnailApi) { failures[id] = now() } }
            finally { synchronized(this@ThumbnailApi) { jobs -= id; while (failures.size > 2000) failures.remove(failures.keys.first()) } }
        }
        return response(202, "THUMBNAIL_PENDING")
    }
    private fun prune() {
        val files = directory.listFiles().orEmpty().filter { it.name.endsWith(".jpg") }.sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        files.forEach { if (bytes > maxBytes) { val size = it.length(); if (it.delete()) bytes -= size } }
    }
    fun close() = worker.cancel()
    private fun response(code: Int, error: String) = ApiResponse(code, JSONObject().put("error", error).toString())
    private fun jpeg(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[bytes.lastIndex-1] == 0xff.toByte() && bytes.last() == 0xd9.toByte()
    companion object { const val MAX_IMAGE_BYTES = 96 * 1024 }
}

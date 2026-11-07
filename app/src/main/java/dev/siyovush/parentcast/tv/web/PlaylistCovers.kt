package dev.siyovush.parentcast.tv.web

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject

/** Only derived JPEG images. The caller authenticates and checks queue membership first. */
internal class PlaylistCovers(
    private val directory: File,
    private val normalize: (ByteArray) -> ByteArray?,
    private val captureFrame: (String, Long) -> ByteArray?,
    private val maxStoredBytes: Long = 64L * 1024 * 1024,
) {
    init { check(directory.isDirectory || directory.mkdirs()) }

    fun upload(length: Long, input: InputStream): ApiResponse {
        if (length !in 1..MAX_INPUT_BYTES) return failure(413, "COVER_TOO_LARGE")
        return try {
            val bytes = input.readNBytes(length.toInt())
            if (bytes.size.toLong() != length) return failure(400, "COVER_INCOMPLETE")
            val jpeg = normalize(bytes) ?: return failure(400, "UNSUPPORTED_IMAGE")
            save(jpeg)
        } catch (_: Exception) { failure(503, "COVER_STORAGE_ERROR") }
    }

    fun frame(videoId: String, positionMs: Long): ApiResponse {
        if (!dev.siyovush.parentcast.tv.prepared.MEDIA_ID.matches(videoId) || positionMs !in 0..86_400_000L)
            return failure(400, "BAD_REQUEST")
        return try {
            val jpeg = captureFrame(videoId, positionMs) ?: return failure(409, "FRAME_UNAVAILABLE")
            save(jpeg)
        } catch (_: Exception) { failure(503, "COVER_STORAGE_ERROR") }
    }

    @Synchronized private fun save(jpeg: ByteArray): ApiResponse {
        if (jpeg.size !in 4..MAX_IMAGE_BYTES || jpeg[0] != 0xff.toByte() || jpeg[1] != 0xd8.toByte() ||
            jpeg[jpeg.lastIndex - 1] != 0xff.toByte() || jpeg.last() != 0xd9.toByte())
            return failure(400, "UNSUPPORTED_IMAGE")
        val id = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }
        val target = File(directory, "$id.jpg")
        if (!target.isFile) {
            if (directory.listFiles().orEmpty().sumOf { it.length() } + jpeg.size > maxStoredBytes)
                return failure(409, "COVER_STORAGE_FULL")
            val temp = File(directory, "$id.tmp")
            try {
                FileOutputStream(temp).use { it.write(jpeg); it.fd.sync() }
                check(temp.renameTo(target))
            } finally { temp.delete() }
        }
        return ApiResponse(200, JSONObject().put("coverImageId", id).toString())
    }

    fun exists(id: String): Boolean = validId(id) && File(directory, "$id.jpg").let {
        it.isFile && it.length() in 4..MAX_IMAGE_BYTES.toLong()
    }

    fun read(id: String, allowed: Boolean): ApiResponse {
        if (!validId(id)) return failure(400, "BAD_COVER_ID")
        if (!allowed || !exists(id)) return failure(404, "COVER_NOT_FOUND")
        return try { ApiResponse(200, JSONObject().put("image", "data:image/jpeg;base64," +
            Base64.getEncoder().encodeToString(File(directory, "$id.jpg").readBytes())).toString()) }
        catch (_: Exception) { failure(503, "COVER_STORAGE_ERROR") }
    }

    fun imageFile(id: String): File? = if (exists(id)) File(directory, "$id.jpg") else null

    private fun failure(code: Int, error: String) = ApiResponse(code, JSONObject().put("error", error).toString())
    companion object {
        const val MAX_INPUT_BYTES = 4L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 160 * 1024
        fun validId(id: String) = id.matches(Regex("[0-9a-f]{64}"))
    }
}

internal object CoverImages {
    fun normalize(bytes: ByteArray): ByteArray? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/webp") ||
            bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192 ||
            bounds.outWidth.toLong() * bounds.outHeight > 32_000_000L) return null
        val sample = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = maxOf(bounds.outWidth / 1280, bounds.outHeight / 720, 1)
        }
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sample)?.let(::encode)
    }

    fun frame(file: File?, positionMs: Long): ByteArray? {
        if (file == null || !file.isFile) return null
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            // An explicit descriptor avoids firmware-specific path/URI handling.
            java.io.FileInputStream(file).use { retriever.setDataSource(it.fd) }
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val point = positionMs.coerceAtMost((duration - 1).coerceAtLeast(0))
            retriever.getScaledFrameAtTime(point * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 360)?.let(::encode)
        } catch(error:RuntimeException) {
            android.util.Log.w("ParentCastCovers","Frame decode: " + error.javaClass.simpleName + ": " + error.message?.take(160))
            throw error
        } finally { retriever.release() }
    }

    private fun encode(bitmap: android.graphics.Bitmap): ByteArray? {
        val scale = minOf(640.0 / bitmap.width, 360.0 / bitmap.height, 1.0)
        val small = if (scale < 1) android.graphics.Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        return try {
            val output = java.io.ByteArrayOutputStream()
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, output)
            output.toByteArray().takeIf { it.size <= PlaylistCovers.MAX_IMAGE_BYTES }
        } finally { if (small !== bitmap) small.recycle(); bitmap.recycle() }
    }
}

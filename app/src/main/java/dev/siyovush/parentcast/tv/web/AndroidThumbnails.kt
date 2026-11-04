package dev.siyovush.parentcast.tv.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.siyovush.parentcast.tv.media.OnlineMediaIds
import dev.siyovush.parentcast.tv.media.VkMediaPolicy
import dev.siyovush.parentcast.tv.youtube.ytdlp.YtDlpRuntime
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class AndroidThumbnails(context: Context, private val localFile: (String) -> File?) {
    private val runtime = YtDlpRuntime(context.applicationContext)
    private val hints = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val http = OkHttpClient.Builder().dns(VkMediaPolicy.dns).followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(7, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    fun remember(id: String, url: String?) {
        if (OnlineMediaIds.isOnline(id) && url?.toHttpUrlOrNull()?.let(ThumbnailPolicy::allows) == true) {
            if (hints.size < 5000) hints[id] = url
        }
    }
    suspend fun generate(id: String): ByteArray? {
        val file = localFile(id)
        // Local/VK files are already on the box: no network or extra extractor needed.
        if (file != null && (id.startsWith("local_") || OnlineMediaIds.isVk(id))) {
            hints[id]?.let { runCatching { download(it) }.getOrNull() }?.let { return it }
            frame(file)?.let { return it }
        }
        val url = when {
            id.matches(Regex("[A-Za-z0-9_-]{11}")) -> "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            OnlineMediaIds.isVk(id) -> hints[id] ?: vkImage(id)?.also { remember(id, it) }
            else -> null
        }
        if (url != null) runCatching { download(url) }.getOrNull()?.let { return it }
        return file?.let(::frame)
    }
    private suspend fun vkImage(id: String): String? = runInterruptible(Dispatchers.IO) {
        runtime.ensureInitialized()
        val pid = "parentcast-thumbnail-${UUID.randomUUID()}"
        try {
            val request = YoutubeDLRequest(OnlineMediaIds.videoUrl(id)).addOption("--ignore-config")
                .addOption("--no-cache-dir").addOption("--no-playlist").addOption("--use-extractors", "^vk$")
                .addOption("--skip-download").addOption("--dump-single-json").addOption("--socket-timeout", 7)
                .addOption("--retries", 0).addOption("--extractor-retries", 0)
            val response = YoutubeDL.getInstance().execute(request, pid, false, null)
            if (response.out.length > 2 * 1024 * 1024) return@runInterruptible null
            val node = YoutubeDL.objectMapper.readTree(response.out)
            if (node.path("id").asText() != id.removePrefix("vk:") || node.path("extractor_key").asText() != "VK") return@runInterruptible null
            (listOf(node.path("thumbnail").asText()) + node.path("thumbnails").map { it.path("url").asText() })
                .lastOrNull { it.toHttpUrlOrNull()?.let(ThumbnailPolicy::allows) == true }
        } finally { YoutubeDL.getInstance().destroyProcessById(pid) }
    }
    private fun download(value: String): ByteArray? {
        var url = value.toHttpUrlOrNull() ?: return null
        repeat(4) {
            if (!ThumbnailPolicy.allows(url)) return null
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isRedirect) {
                    url = response.header("Location")?.let(url::resolve) ?: return null
                } else {
                    if (!response.isSuccessful) return null
                    val body = response.body ?: return null
                    if (body.contentLength() > 2 * 1024 * 1024) return null
                    val bytes = body.byteStream().use { it.readNBytes(2 * 1024 * 1024 + 1) }
                    if (bytes.size > 2 * 1024 * 1024) return null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192) return null
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = (minOf(bounds.outWidth / 320, bounds.outHeight / 180)).coerceAtLeast(1)
                    }
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let(::encode)
                }
            }
        }
        return null
    }
    private fun frame(file: File): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val timeUs = minOf(3_000L, duration / 10) * 1000
            (retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                ?: retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180))?.let(::encode)
        } finally { retriever.release() }
    }.getOrNull()
    private fun encode(bitmap: Bitmap): ByteArray? {
        val scale = minOf(320.0 / bitmap.width, 180.0 / bitmap.height, 1.0)
        val small = if (scale < 1) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        return try {
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 75, out)
            out.toByteArray().takeIf { it.size <= ThumbnailApi.MAX_IMAGE_BYTES }
        } finally { if (small !== bitmap) small.recycle(); bitmap.recycle() }
    }
}

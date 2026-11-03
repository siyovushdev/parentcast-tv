package dev.siyovush.parentcast.tv.prepared

import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

internal class HttpTrackDownloader(private val session: YouTubeSession) : TrackDownloader {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(2, TimeUnit.HOURS).build()
    override suspend fun download(spec: StreamSpec, target: File, allowance: Long, progress: (Long, Long) -> Unit): LocalTrack = coroutineScope {
        var url = spec.url.toHttpUrl()
        fun requireCdn() { require(url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.host.endsWith(".googlevideo.com") && url.host.length > ".googlevideo.com".length) { "UNSUPPORTED" } }
        val partial = File(target.path + ".part")
        partial.delete()
        try {
            repeat(6) {
                requireCdn()
                currentCoroutineContext().ensureActive()
                val builder = Request.Builder().url(url).header("User-Agent", YOUTUBE_USER_AGENT).header("Accept-Encoding", "identity")
                spec.headers.filterKeys { it.lowercase() in setOf("accept", "accept-language", "origin", "referer") }.forEach { (k, v) -> builder.header(k, v) }
                CookieHeaderCodec.merge(emptyList(), session.cookiesFor(url))?.let { builder.header("Cookie", it) }
                val call = client.newCall(builder.build())
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
                try {
                    val result = withContext(Dispatchers.IO) {
                        call.execute().use { response ->
                            if (response.code in setOf(301, 302, 303, 307, 308)) {
                                url = url.resolve(response.header("Location").orEmpty()) ?: error("UNSUPPORTED")
                                null
                            } else {
                                check(response.code == 200) { "DOWNLOAD_FAILED" }
                                val body = response.body ?: error("DOWNLOAD_FAILED")
                                val total = body.contentLength()
                                val operationContext = currentCoroutineContext()
                                val bytes = body.byteStream().use { input ->
                                    copyCompleteTrack(input, partial, total, allowance,
                                        { target.parentFile!!.usableSpace }, { operationContext.ensureActive() }, progress)
                                }
                                currentCoroutineContext().ensureActive()
                                check(partial.renameTo(target)) { "DOWNLOAD_FAILED" }
                                LocalTrack(target.name, bytes, sha256(target))
                            }
                        }
                    }
                    if (result != null) return@coroutineScope result
                } finally { cancellation.cancel() }
            }
            error("DOWNLOAD_FAILED")
        } finally { partial.delete() }
    }
}

// A partial or unknown-length response never becomes a playable file.
internal fun copyCompleteTrack(
    input: java.io.InputStream, partial: File, total: Long, allowance: Long,
    freeBytes: () -> Long, checkCancellation: () -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> },
): Long {
    check(total > 0) { "UNSUPPORTED" }
    check(total <= allowance && freeBytes() - total >= FREE_RESERVE) { "NO_SPACE" }
    var bytes = 0L; var lastProgress = 0L
    try {
        FileOutputStream(partial).use { output ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancellation()
                val count = input.read(buffer); if (count < 0) break
                check(bytes + count <= total && bytes + count <= allowance) { "DOWNLOAD_FAILED" }
                check(freeBytes() - count >= FREE_RESERVE) { "NO_SPACE" }
                output.write(buffer, 0, count); bytes += count
                val now = System.nanoTime()
                if (now - lastProgress > 500_000_000L) { progress(bytes, total); lastProgress = now }
            }
            output.fd.sync()
        }
        check(bytes == total) { "DOWNLOAD_FAILED" }
        checkCancellation(); progress(bytes, total)
        return bytes
    } catch (error: Exception) { partial.delete(); throw error }
}

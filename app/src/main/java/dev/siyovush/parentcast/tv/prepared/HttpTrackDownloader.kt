package dev.siyovush.parentcast.tv.prepared

import dev.siyovush.parentcast.tv.youtube.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

internal class HttpTrackDownloader(
    private val session: YouTubeSession,
    private val budget: MediaBudget? = null,
    private val transferEvent: (String,Long) -> Unit = { _,_-> },
    vkTransport: OkHttpClient? = null,
    private val allowed: (HttpUrl) -> Boolean = { it.scheme == "https" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() && it.host.endsWith(".googlevideo.com") && it.host.length > ".googlevideo.com".length },
) : TrackDownloader {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(2, TimeUnit.HOURS).build()
    private val vkClient = vkTransport ?: client.newBuilder().dns(dev.siyovush.parentcast.tv.media.VkMediaPolicy.dns).build()
    override suspend fun download(spec: StreamSpec, target: File, allowance: Long, progress: (Long, Long) -> Unit): LocalTrack = coroutineScope {
        val isVk = spec.provider == dev.siyovush.parentcast.tv.media.MediaProvider.VK
        val transferClient = if (isVk) vkClient else client
        val transferContext=currentCoroutineContext()
        var url = spec.url.toHttpUrl()
        val files = ResumeFiles(target)
        var saved = files.read()?.takeIf { spec.resumeIdentity.isNotEmpty() && it.identity == spec.resumeIdentity && (spec.sizeBytes == null || spec.sizeBytes == it.total) }
        if (saved == null) files.reset()
        if (saved?.complete == true && target.exists() && (target.length() != saved.total || sha256(target) { transferContext.ensureActive() } != saved.sha)) {
            files.reset(); saved = null
        }
        // A completed track with provider revision + checksum is reusable while a sibling track retries.
        if (saved?.complete == true && spec.resumeIdentity.contains("|revision=") && target.length() == saved.total && sha256(target) { transferContext.ensureActive() } == saved.sha) {
            transferEvent("REUSED_COMPLETE_TRACK",saved.total)
            progress(saved.total, saved.total); return@coroutineScope LocalTrack(target.name, saved.total, saved.sha)
        }
        if (saved?.complete == true && !files.partial.exists()) { check(target.renameTo(files.partial)); saved = saved.copy(complete = false) }
        var offset = if (saved != null && saved.validator.isNotEmpty()) files.partial.length() else 0L
        if (offset <= 0 || offset > (saved?.total ?: 0)) { files.reset(); saved = null; offset = 0 }
        repeat(8) {
            require(if (isVk) dev.siyovush.parentcast.tv.media.VkMediaPolicy.allows(url) else allowed(url)) { "UNSUPPORTED" }
            currentCoroutineContext().ensureActive()
            val builder = Request.Builder().url(url).header("User-Agent", if(isVk) spec.headers.entries.firstOrNull { it.key.equals("User-Agent",true) }?.value ?: YOUTUBE_USER_AGENT else YOUTUBE_USER_AGENT).header("Accept-Encoding", "identity")
            spec.headers.filterKeys { it.lowercase() in setOf("accept", "accept-language", "origin", "referer") }.forEach { (k, v) -> builder.header(k, v) }
            (if (isVk) null else CookieHeaderCodec.merge(emptyList(), session.cookiesFor(url)))?.let { builder.header("Cookie", it) }
            if (offset > 0 && saved != null) { builder.header("Range", "bytes=$offset-"); builder.header("If-Range", saved!!.validator) }
            val call = transferClient.newCall(builder.build())
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try {
                val result = withContext(Dispatchers.IO) {
                    call.execute().use { response ->
                        if (response.code in setOf(301,302,303,307,308)) {
                            url = url.resolve(response.header("Location").orEmpty()) ?: error("UNSUPPORTED")
                            return@use null
                        }
                        if (response.code in setOf(403,408,410,429,500,502,503,504)) throw RetryableTransfer("HTTP_${response.code}", response.header("Retry-After")?.toLongOrNull()?.times(1000)?.coerceIn(0,600_000) ?: 0)
                        val validator = strongResumeValidator(response.header("ETag"), response.header("Last-Modified"), response.header("Date"))
                        val old = saved
                        val same = old != null && validator?.first == old.validator && validator.second == old.etag
                        if (response.code == 416) {
                            if (offset > 0 && old != null && offset == old.total && same && response.header("Content-Range") == "bytes */${old.total}") {
                                check(files.partial.renameTo(target)); val sha = sha256(target) { transferContext.ensureActive() }
                                files.write(old.copy(complete = true, sha = sha)); progress(old.total, old.total)
                                return@use LocalTrack(target.name, old.total, sha)
                            }
                            files.reset(); saved = null; offset = 0; return@use null
                        }
                        check(response.code in setOf(200,206)) { "DOWNLOAD_FAILED" }
                        val body = response.body ?: throw RetryableTransfer("EMPTY_RESPONSE")
                        val range = parseContentRange(response.header("Content-Range"))
                        if (response.code == 206 && (offset <= 0 || old == null || !same || range == null || range.start != offset || range.total != old.total || range.end != old.total - 1 || body.contentLength() != range.end - range.start + 1)) {
                            files.reset(); saved = null; offset = 0; return@use null
                        }
                        if(response.code==206)transferEvent("RESUMED_206",offset)
                        if (response.code == 200) { if(offset>0)transferEvent("RESTARTED_200",offset);offset = 0; files.reset() }
                        val total = if (response.code == 206) range!!.total else body.contentLength()
                        check(total > 0) { "UNSUPPORTED" }
                        if(spec.sizeBytes!=null && spec.sizeBytes!=total) throw RetryableTransfer("REPRESENTATION_CHANGED")
                        check(total <= allowance && target.parentFile!!.usableSpace - (total - offset) >= FREE_RESERVE) { "NO_SPACE" }
                        val record = ResumeRecord(spec.resumeIdentity, total, validator?.first.orEmpty(), validator?.second ?: false)
                        files.write(record)
                        val operationContext = currentCoroutineContext()
                        var bytes = offset; var lastProgress = 0L
                        progress(bytes, total)
                        body.byteStream().use { input ->
                            (try {FileOutputStream(files.partial, offset > 0)} catch(error:java.io.IOException){throw MediaStorageFailure(error)}).use { output ->
                                copyDownloadBlocks(input, { operationContext.ensureActive() }) { buffer, count ->
                                    operationContext.ensureActive()
                                    check(bytes + count <= total && bytes + count <= allowance) { "DOWNLOAD_FAILED" }
                                    if (budget != null) budget.write(output, buffer, count,target) else {
                                        check(target.parentFile!!.usableSpace - count >= FREE_RESERVE) { "NO_SPACE" }
                                        try {output.write(buffer, 0, count)} catch(error:java.io.IOException){throw MediaStorageFailure(error)}
                                    }
                                    bytes += count
                                    val now = System.nanoTime(); if (now - lastProgress > 500_000_000L) { progress(bytes,total); lastProgress=now }
                                }
                                try {output.fd.sync()} catch(error:java.io.IOException){throw MediaStorageFailure(error)}
                            }
                        }
                        if (bytes != total) throw RetryableTransfer("INCOMPLETE_RESPONSE")
                        operationContext.ensureActive(); check(files.partial.renameTo(target)) { "DOWNLOAD_FAILED" }
                        val sha = sha256(target) { transferContext.ensureActive() }; files.write(record.copy(complete = true, sha = sha)); progress(bytes,total)
                        LocalTrack(target.name,bytes,sha)
                    }
                }
                if (result != null) return@coroutineScope result
            } catch (error: java.io.IOException) {
                if(!currentCoroutineContext().isActive) throw CancellationException("Transfer cancelled",error)
                throw error
            } finally { cancellation.cancel() }
        }
        throw RetryableTransfer("RANGE_OR_REDIRECT_FAILED")
    }
}

/** Network reads may return only a TLS/Okio packet despite the larger buffer. Perform
 * the live volume/quota gate per bounded physical write, not per network packet.
 * Preserve an incomplete block on network failure so the existing Range retry can use it. */
internal fun copyDownloadBlocks(
    input: java.io.InputStream,
    checkCancellation: () -> Unit,
    write: (ByteArray, Int) -> Unit,
) {
    val buffer = ByteArray(128 * 1024)
    var buffered = 0
    while (true) {
        checkCancellation()
        val count = try { input.read(buffer, buffered, buffer.size - buffered) }
        catch (error: java.io.IOException) {
            if (buffered > 0) { checkCancellation(); write(buffer, buffered) }
            throw error
        }
        if (count < 0) {
            if (buffered > 0) { checkCancellation(); write(buffer, buffered) }
            return
        }
        buffered += count
        if (buffered == buffer.size) {
            checkCancellation(); write(buffer, buffered); buffered = 0
        }
    }
}

// Legacy one-shot copy helper: unknown-length/partial responses never become playable.
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
                checkCancellation(); val count = input.read(buffer); if (count < 0) break
                check(bytes + count <= total && bytes + count <= allowance) { "DOWNLOAD_FAILED" }
                check(freeBytes() - count >= FREE_RESERVE) { "NO_SPACE" }
                output.write(buffer, 0, count); bytes += count
                val now = System.nanoTime(); if (now - lastProgress > 500_000_000L) { progress(bytes, total); lastProgress = now }
            }
            output.fd.sync()
        }
        check(bytes == total) { "DOWNLOAD_FAILED" }; checkCancellation(); progress(bytes,total); return bytes
    } catch (error: Exception) { partial.delete(); throw error }
}

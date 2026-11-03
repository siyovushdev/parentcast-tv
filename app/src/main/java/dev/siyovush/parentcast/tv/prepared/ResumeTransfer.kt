package dev.siyovush.parentcast.tv.prepared

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class RetryableTransfer(val code: String, val retryAfterMs: Long = 0) : java.io.IOException(code)
internal data class ResumeRecord(val identity: String, val total: Long, val validator: String, val etag: Boolean, val complete: Boolean = false, val sha: String = "")
internal class ResumeFiles(val target: File) {
    val partial = File(target.path + ".part")
    private val metadata = File(target.path + ".resume.json")
    fun read(): ResumeRecord? = runCatching {
        val j = JSONObject(metadata.readText())
        ResumeRecord(j.getString("identity"), j.getLong("total"), j.getString("validator"), j.getBoolean("etag"), j.optBoolean("complete"), j.optString("sha"))
            .also { require(it.total > 0 && it.identity.length <= 300 && it.validator.length <= 300 && '\r' !in it.validator && '\n' !in it.validator) }
    }.getOrNull()
    fun write(r: ResumeRecord) {
        val j = JSONObject().put("identity", r.identity).put("total", r.total).put("validator", r.validator).put("etag", r.etag).put("complete", r.complete).put("sha", r.sha)
        val temp = File(metadata.path + ".tmp")
        try { FileOutputStream(temp).use { it.write(j.toString().toByteArray()); it.fd.sync() } }
        catch(error:java.io.IOException) {throw MediaStorageFailure(error)}
        check(temp.renameTo(metadata)) { "DOWNLOAD_FAILED" }
    }
    fun reset() { partial.delete(); target.delete(); metadata.delete(); File(metadata.path + ".tmp").delete() }
}
internal fun strongResumeValidator(etag: String?, modified: String?, date: String?): Pair<String, Boolean>? {
    if (etag != null) return if (etag.startsWith('"') && etag.endsWith('"') && etag.length in 2..300 && !etag.startsWith("W/") && etag.drop(1).dropLast(1).all { it.code==0x21 || it.code in 0x23..0x7e || it.code in 0x80..0xff }) etag to true else null
    return runCatching {
        val last = ZonedDateTime.parse(modified, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        val current = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        // Conservative client test for a strong Last-Modified date; never use an unknown/weak date.
        if (modified!!.length <= 300 && java.time.Duration.between(last, current).seconds >= 60) modified to false else null
    }.getOrNull()
}
internal data class ContentRange(val start: Long, val end: Long, val total: Long)
internal fun parseContentRange(value: String?): ContentRange? = runCatching {
    val m = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(value.orEmpty()) ?: return null
    val (a, b, c) = m.destructured
    ContentRange(a.toLong(), b.toLong(), c.toLong()).also { require(it.start >= 0 && it.end >= it.start && it.total > it.end) }
}.getOrNull()

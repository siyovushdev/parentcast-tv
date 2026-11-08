package dev.siyovush.parentcast.tv.prepared

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class RetryableTransfer(val code: String, val retryAfterMs: Long = 0) : java.io.IOException(code)
internal data class ResumeRecord(val identity: String, val total: Long, val validator: String, val etag: Boolean, val complete: Boolean = false, val sha: String = "",
    val integrityVersion: Int = 1, val blocks: List<IntegrityBlock> = emptyList())
internal class ResumeFiles(val target: File) {
    val partial = File(target.path + ".part")
    private val metadata = File(target.path + ".resume.json")
    fun read(): ResumeRecord? = runCatching {
        require(metadata.length() <= 2 * 1024 * 1024)
        val j = JSONObject(metadata.readText())
        val blocks = j.optJSONArray("blocks")?.let { a ->
            require(a.length() <= 16_384)
            List(a.length()) { i -> val b = a.getJSONObject(i)
                IntegrityBlock(b.getInt("bytes"), b.getString("sha256")).also {
                    require(it.bytes in 1..INTEGRITY_BLOCK_BYTES && it.sha256.matches(Regex("[0-9a-f]{64}")))
                }
            }
        }.orEmpty()
        ResumeRecord(j.getString("identity"), j.getLong("total"), j.getString("validator"), j.getBoolean("etag"), j.optBoolean("complete"), j.optString("sha"),j.optInt("integrityVersion",0),blocks)
            .also { require(it.total > 0 && blocks.sumOf { b -> b.bytes.toLong() } <= it.total && it.identity.length <= 300 && it.validator.length <= 300 && '\r' !in it.validator && '\n' !in it.validator) }
    }.getOrNull()
    fun write(r: ResumeRecord) {
        val j = JSONObject().put("identity", r.identity).put("total", r.total).put("validator", r.validator).put("etag", r.etag).put("complete", r.complete).put("sha", r.sha)
            .put("integrityVersion",r.integrityVersion).put("blocks",JSONArray().also { a -> r.blocks.forEach { a.put(JSONObject().put("bytes",it.bytes).put("sha256",it.sha256)) } })
        val temp = File(metadata.path + ".tmp")
        try { FileOutputStream(temp).use { it.write(j.toString().toByteArray()); it.fd.sync() } }
        catch(error:java.io.IOException) {throw MediaStorageFailure(error)}
        check(temp.renameTo(metadata)) { "DOWNLOAD_FAILED" }
    }
    fun reset() { partial.delete(); target.delete(); metadata.delete(); File(metadata.path + ".tmp").delete() }
    fun discardUnpublished() {
        for (file in listOf(partial,target,metadata,File(metadata.path+".tmp"))) {
            if (file.exists() && !file.delete()) throw MediaStorageFailure(java.io.IOException("Discard failed"))
        }
    }
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

package dev.siyovush.parentcast.tv.prepared

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal val VIDEO_ID = Regex(dev.siyovush.parentcast.tv.media.OnlineMediaIds.ONLINE_PATTERN)
internal val LOCAL_ID = Regex("local_[0-9a-f]{32}")
internal val MEDIA_ID = Regex(dev.siyovush.parentcast.tv.media.OnlineMediaIds.MEDIA_PATTERN)
internal const val LIBRARY_LIMIT = 10L * 1024 * 1024 * 1024
internal const val FREE_RESERVE = 1024L * 1024 * 1024
internal enum class PreparationStatus { WAITING, DOWNLOADING, READY, ERROR, DELETED, RETRY_WAIT }
internal data class LocalTrack(val name: String, val bytes: Long, val sha256: String)
internal data class PreparedEntry(
    val id: String, val status: PreparationStatus = PreparationStatus.WAITING,
    val title: String = "", val duration: Long = 0, val bytes: Long = 0,
    val total: Long = 0, val error: String = "", val tracks: List<LocalTrack> = emptyList(),
    val phase: String = "", val totalKnown: Boolean = false,
    val local: Boolean = false, val mime: String = "video/mp4",
    val attempts: Int = 0, val retryAtMs: Long = 0,
)
internal data class PreparationState(
    val enabled: Boolean = false, val paused: Boolean = false,
    val plan: List<String> = emptyList(), val entries: Map<String, PreparedEntry> = emptyMap(),
    val storageError: Boolean = false,
    val deleting: List<String> = emptyList(), val operationError: String = "",
)
internal class PreparedStore(val directory: File) {
    private val index = File(directory, "index.json")
    init { directory.mkdirs() }
    fun track(id: String, audio: Boolean = false) = File(directory, "$id.${if (audio) "audio" else "video"}.mp4").also {
        require(MEDIA_ID.matches(id))
    }
    fun read(): PreparationState {
        if (!index.exists()) return PreparationState()
        return try {
            val root = JSONObject(index.readText())
            val plan = root.getJSONArray("plan").let { a -> List(a.length()) { a.getString(it).also { id -> require(VIDEO_ID.matches(id)) } } }
            require(plan.size <= 10_000)
            val entries = root.getJSONArray("entries").let { a -> (0 until a.length()).associate {
                val e = a.getJSONObject(it); val id = e.getString("id"); require(MEDIA_ID.matches(id)); require(e.optBoolean("local") == LOCAL_ID.matches(id))
                val tracks = e.getJSONArray("tracks").let { t -> List(t.length()) { i ->
                    val v = t.getJSONObject(i); val name = v.getString("name")
                    require(name in listOf("$id.video.mp4", "$id.audio.mp4"))
                    LocalTrack(name, v.getLong("bytes"), v.getString("sha256"))
                } }
                val status = PreparationStatus.valueOf(e.getString("status"))
                id to PreparedEntry(id, if (status == PreparationStatus.DOWNLOADING) PreparationStatus.WAITING else status,
                    e.optString("title"), e.optLong("duration"), e.optLong("bytes"), e.optLong("total"), e.optString("error"), tracks, e.optString("phase"), e.optBoolean("totalKnown"), e.optBoolean("local"), e.optString("mime", "video/mp4"), e.optInt("attempts"), e.optLong("retryAtMs")).let { entry ->
                        if (status == PreparationStatus.DOWNLOADING) entry.copy(tracks = emptyList(), phase = "WAITING")
                        else if(root.optInt("resumeVersion",0)==0 && !entry.local && status==PreparationStatus.ERROR && entry.error=="DOWNLOAD_FAILED") entry.copy(status=PreparationStatus.RETRY_WAIT,error="NETWORK_RETRY",phase="RETRY",retryAtMs=0)
                        else entry
                    }
            } }
            PreparationState(root.getBoolean("enabled"), root.optBoolean("paused"), plan, entries,
                deleting = root.optJSONArray("deleting")?.let { a -> List(a.length()) { a.getString(it).also { id -> require(MEDIA_ID.matches(id)) } } }.orEmpty(),
                operationError = root.optString("operationError"))
        } catch (_: Exception) { PreparationState(storageError = true) }
    }
    fun write(s: PreparationState) {
        check(!s.storageError) { "Preparation index needs recovery" }
        val entries = JSONArray()
        s.entries.values.forEach { e ->
            val tracks = JSONArray(); e.tracks.forEach { t -> tracks.put(JSONObject().put("name", t.name).put("bytes", t.bytes).put("sha256", t.sha256)) }
            entries.put(JSONObject().put("id", e.id).put("status", e.status.name).put("title", e.title).put("duration", e.duration)
                .put("bytes", e.bytes).put("total", e.total).put("error", e.error).put("tracks", tracks).put("phase", e.phase).put("totalKnown", e.totalKnown).put("local", e.local).put("mime", e.mime).put("attempts", e.attempts).put("retryAtMs", e.retryAtMs))
        }
        val root = JSONObject().put("resumeVersion",1).put("enabled", s.enabled).put("paused", s.paused).put("plan", JSONArray(s.plan)).put("entries", entries).put("deleting", JSONArray(s.deleting)).put("operationError", s.operationError)
        val temporary = File(directory, "index.tmp")
        FileOutputStream(temporary).use { it.write(root.toString().toByteArray()); it.fd.sync() }
        check(temporary.renameTo(index)) { "Preparation index write failed" }
    }
    fun verify(e: PreparedEntry, checkCancellation: () -> Unit = {}): Boolean = e.status == PreparationStatus.READY && e.tracks.isNotEmpty() && e.tracks.all { t ->
        val f = File(directory, t.name)
        f.isFile && f.length() == t.bytes && sha256(f,checkCancellation) == t.sha256
    }
    fun delete(id: String) { mediaFiles(id).forEach { it.delete() } }
    private fun mediaFiles(id: String) = listOf(track(id), track(id, true)).flatMap { listOf(it, File(it.path + ".part"), File(it.path + ".resume.json"), File(it.path + ".resume.json.tmp")) }
    fun retainedBytes(id: String): Long = listOf(track(id), track(id,true)).sumOf { if (it.isFile) it.length() else File(it.path+".part").length() }
    fun mediaIds(): Set<String> = directory.listFiles().orEmpty().map { it.name }
        .filter { it.matches(Regex(dev.siyovush.parentcast.tv.media.OnlineMediaIds.MEDIA_PATTERN + "[.](video|audio)[.]mp4([.]part)?")) }.map { it.substringBefore('.') }.toSet()
    fun deleteChecked(id: String) {
        mediaFiles(id).forEach { file ->
            check(!file.exists() || file.delete()) { "DELETE_FAILED" }
        }
    }
    fun usedBytes(): Long = directory.listFiles().orEmpty().filter { it.name.endsWith(".mp4") || it.name.endsWith(".part") }.sumOf { it.length() }
}
internal fun sha256(file: File, checkCancellation: () -> Unit = {}): String = MessageDigest.getInstance("SHA-256").let { digest ->
    file.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) { checkCancellation(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
    digest.digest().joinToString("") { "%02x".format(it) }
}

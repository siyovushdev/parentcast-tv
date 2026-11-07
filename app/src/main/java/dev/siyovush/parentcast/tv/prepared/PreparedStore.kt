package dev.siyovush.parentcast.tv.prepared

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
internal const val LIBRARY_LIMIT = 2L * 1024 * 1024 * 1024
internal const val FREE_RESERVE = 1024L * 1024 * 1024
internal enum class PreparationStatus { WAITING, DOWNLOADING, READY, ERROR }
internal data class LocalTrack(val name: String, val bytes: Long, val sha256: String)
internal data class PreparedEntry(
    val id: String, val status: PreparationStatus = PreparationStatus.WAITING,
    val title: String = "", val duration: Long = 0, val bytes: Long = 0,
    val total: Long = 0, val error: String = "", val tracks: List<LocalTrack> = emptyList(),
)
internal data class PreparationState(
    val enabled: Boolean = false, val paused: Boolean = false,
    val plan: List<String> = emptyList(), val entries: Map<String, PreparedEntry> = emptyMap(),
    val storageError: Boolean = false,
)
internal class PreparedStore(val directory: File) {
    private val index = File(directory, "index.json")
    init { directory.mkdirs() }
    fun track(id: String, audio: Boolean = false) = File(directory, "$id.${if (audio) "audio" else "video"}.mp4").also {
        require(VIDEO_ID.matches(id))
    }
    fun read(): PreparationState {
        if (!index.exists()) return PreparationState()
        return try {
            val root = JSONObject(index.readText())
            val plan = root.getJSONArray("plan").let { a -> List(a.length()) { a.getString(it).also { id -> require(VIDEO_ID.matches(id)) } } }
            require(plan.size <= 10_000)
            val entries = root.getJSONArray("entries").let { a -> (0 until a.length()).associate {
                val e = a.getJSONObject(it); val id = e.getString("id"); require(VIDEO_ID.matches(id))
                val tracks = e.getJSONArray("tracks").let { t -> List(t.length()) { i ->
                    val v = t.getJSONObject(i); val name = v.getString("name")
                    require(name in listOf("$id.video.mp4", "$id.audio.mp4"))
                    LocalTrack(name, v.getLong("bytes"), v.getString("sha256"))
                } }
                val status = PreparationStatus.valueOf(e.getString("status"))
                id to PreparedEntry(id, if (status == PreparationStatus.DOWNLOADING) PreparationStatus.WAITING else status,
                    e.optString("title"), e.optLong("duration"), e.optLong("bytes"), e.optLong("total"), e.optString("error"), tracks)
            } }
            PreparationState(root.getBoolean("enabled"), root.optBoolean("paused"), plan, entries)
        } catch (_: Exception) { PreparationState(storageError = true) }
    }
    fun write(s: PreparationState) {
        check(!s.storageError) { "Preparation index needs recovery" }
        val entries = JSONArray()
        s.entries.values.forEach { e ->
            val tracks = JSONArray(); e.tracks.forEach { t -> tracks.put(JSONObject().put("name", t.name).put("bytes", t.bytes).put("sha256", t.sha256)) }
            entries.put(JSONObject().put("id", e.id).put("status", e.status.name).put("title", e.title).put("duration", e.duration)
                .put("bytes", e.bytes).put("total", e.total).put("error", e.error).put("tracks", tracks))
        }
        val root = JSONObject().put("enabled", s.enabled).put("paused", s.paused).put("plan", JSONArray(s.plan)).put("entries", entries)
        val temporary = File(directory, "index.tmp")
        FileOutputStream(temporary).use { it.write(root.toString().toByteArray()); it.fd.sync() }
        check(temporary.renameTo(index)) { "Preparation index write failed" }
    }
    fun verify(e: PreparedEntry): Boolean = e.status == PreparationStatus.READY && e.tracks.isNotEmpty() && e.tracks.all { t ->
        val f = File(directory, t.name)
        f.isFile && f.length() == t.bytes && sha256(f) == t.sha256
    }
    fun delete(id: String) { listOf(track(id), track(id, true)).forEach { it.delete(); File(it.path + ".part").delete() } }
    fun usedBytes(): Long = directory.listFiles().orEmpty().filter { it.name.endsWith(".mp4") || it.name.endsWith(".part") }.sumOf { it.length() }
}
internal fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
    file.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
    digest.digest().joinToString("") { "%02x".format(it) }
}

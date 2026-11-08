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
    val attempts: Int = 0, val retryAtMs: Long = 0, val integrityAttempts: Int = 0,
)
internal data class PreparationState(
    val enabled: Boolean = false, val paused: Boolean = false,
    val plan: List<String> = emptyList(), val entries: Map<String, PreparedEntry> = emptyMap(),
    val storageError: Boolean = false,
    val targetGroupId: String? = null,
    val deleting: List<String> = emptyList(), val operationError: String = "",
    val explicitSelection: Boolean = false,
)
internal class PreparedStore(val directory: File, storageProvider: (() -> List<MediaVolume>)? = null, internalLimit: Long = LIBRARY_LIMIT) {
    private val index = File(directory, "index.json")
    init { directory.mkdirs() }
    val storage = PreparedStorage(directory, storageProvider ?: { emptyList() }, internalLimit)
    fun trackName(id:String,audio:Boolean=false) = "$id.${if(audio) "audio" else "video"}.mp4"
    fun fileAt(id:String,name:String,storageId:String,write:Boolean=false) = PreparedStorage.safeFile(storage.root(storageId,write),PreparedStorage.physicalName(id,name,storageId!=INTERNAL_STORAGE))
    fun file(id:String,name:String) = fileAt(id,name,storage.placement(id))
    fun track(id: String, audio: Boolean = false):File {
        val storageId=storage.placement(id)
        // Downloaders also write partial/resume siblings; validate those before handing out a target.
        PreparedStorage.files(storage.root(storageId),id,storageId!=INTERNAL_STORAGE)
        return fileAt(id,trackName(id,audio),storageId)
    }
    fun claim(id:String) = storage.claim(listOf(id))
    fun read(): PreparationState {
        if (!index.exists()) return PreparationState(storageError = !storage.healthy())
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
                    e.optString("title"), e.optLong("duration"), e.optLong("bytes"), e.optLong("total"), e.optString("error"), tracks, e.optString("phase"), e.optBoolean("totalKnown"), e.optBoolean("local"), e.optString("mime", "video/mp4"), e.optInt("attempts"), e.optLong("retryAtMs"), e.optInt("integrityAttempts")).let { entry ->
                        if (status == PreparationStatus.DOWNLOADING) entry.copy(tracks = emptyList(), phase = "WAITING")
                        else if(root.optInt("resumeVersion",0)==0 && !entry.local && status==PreparationStatus.ERROR && entry.error=="DOWNLOAD_FAILED") entry.copy(status=PreparationStatus.RETRY_WAIT,error="NETWORK_RETRY",phase="RETRY",retryAtMs=0)
                        else entry
                    }
            } }
            PreparationState(root.getBoolean("enabled"), root.optBoolean("paused"), plan, entries,storageError = !storage.healthy(),
                targetGroupId = root.optString("targetGroupId").takeUnless { it.isBlank() || it=="null" },
                deleting = root.optJSONArray("deleting")?.let { a -> List(a.length()) { a.getString(it).also { id -> require(MEDIA_ID.matches(id)) } } }.orEmpty(),
                operationError = root.optString("operationError"), explicitSelection = root.optBoolean("explicitSelection"))
        } catch (_: Exception) { PreparationState(storageError = true) }
    }
    fun write(s: PreparationState) {
        check(!s.storageError) { "Preparation index needs recovery" }
        val entries = JSONArray()
        s.entries.values.forEach { e ->
            val tracks = JSONArray(); e.tracks.forEach { t -> tracks.put(JSONObject().put("name", t.name).put("bytes", t.bytes).put("sha256", t.sha256)) }
            entries.put(JSONObject().put("id", e.id).put("status", e.status.name).put("title", e.title).put("duration", e.duration)
                .put("bytes", e.bytes).put("total", e.total).put("error", e.error).put("tracks", tracks).put("phase", e.phase).put("totalKnown", e.totalKnown).put("local", e.local).put("mime", e.mime).put("attempts", e.attempts).put("retryAtMs", e.retryAtMs).put("integrityAttempts",e.integrityAttempts))
        }
        val root = JSONObject().put("explicitSelection",s.explicitSelection).put("resumeVersion",1).put("targetGroupId",s.targetGroupId ?: JSONObject.NULL).put("enabled", s.enabled).put("paused", s.paused).put("plan", JSONArray(s.plan)).put("entries", entries).put("deleting", JSONArray(s.deleting)).put("operationError", s.operationError)
        val temporary = File(directory, "index.tmp")
        FileOutputStream(temporary).use { it.write(root.toString().toByteArray()); it.fd.sync() }
        check(temporary.renameTo(index)) { "Preparation index write failed" }
    }
    fun verify(e: PreparedEntry, checkCancellation: () -> Unit = {}): Boolean = e.status == PreparationStatus.READY && e.tracks.isNotEmpty() && e.tracks.all { t ->
        val f = file(e.id,t.name)
        f.isFile && f.length() == t.bytes && sha256(f,checkCancellation) == t.sha256
    }
    fun delete(id: String) { deleteChecked(id) }
    /** Failed network work must never dispose of a retained, verified original copy. */
    fun discardDownload(id:String) { mediaFiles(id).forEach { file -> check(!file.exists() || file.delete()) { "DELETE_FAILED" } } }
    private fun mediaFiles(id: String) = PreparedStorage.files(storage.rootFor(id,true),id,storage.placement(id)!=INTERNAL_STORAGE)
    fun retainedBytes(id: String): Long = runCatching { listOf(track(id), track(id,true)).sumOf { if (it.isFile) it.length() else PreparedStorage.safeFile(it.parentFile!!,it.name+".part").length() } }.getOrDefault(0)
    fun mediaIds(): Set<String> = directory.listFiles().orEmpty().map { it.name }
        .filter { it.matches(Regex(dev.siyovush.parentcast.tv.media.OnlineMediaIds.MEDIA_PATTERN + "[.](video|audio)[.]mp4([.]part)?")) }.map { it.substringBefore('.') }.toSet() + storage.knownIds().filter { retainedBytes(it)>0 }
    fun deleteChecked(id: String) {
        mediaFiles(id).forEach { file ->
            check(!file.exists() || file.delete()) { "DELETE_FAILED" }
        }
        storage.retained().filter { it.id==id }.forEach { original ->
            PreparedStorage.files(storage.root(original.storageId,true),id,original.storageId!=INTERNAL_STORAGE).forEach { file -> check(!file.exists() || file.delete()) { "DELETE_FAILED" } }
            storage.removeOriginal(original)
        }
        discardStaged(id)
    }
    fun discardStaged(id:String?=null,targetId:String?=null) {
        storage.stagedCopies().filter { (id==null || it.id==id) && (targetId==null || it.storageId==targetId) }.forEach { copy ->
            // Placement + stage removal are one journal commit. Never remove a committed active copy.
            if(storage.placement(copy.id)!=copy.storageId) {
                val file=fileAt(copy.id,copy.track.name,copy.storageId,true)
                listOf(file,PreparedStorage.safeFile(file.parentFile!!,file.name+".copying")).forEach { check(!it.exists() || it.delete()) { "DELETE_FAILED" } }
            }
            storage.forgetStage(copy)
        }
    }
    fun usedBytes(): Long = storage.usedBytes(storage.selectedId())
    fun usedBytesFor(id:String):Long = storage.usedBytes(storage.placement(id))
}
internal fun sha256(file: File, checkCancellation: () -> Unit = {}): String = MessageDigest.getInstance("SHA-256").let { digest ->
    file.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) { checkCancellation(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
    digest.digest().joinToString("") { "%02x".format(it) }
}

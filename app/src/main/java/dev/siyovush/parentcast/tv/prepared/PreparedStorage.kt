package dev.siyovush.parentcast.tv.prepared

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

internal const val INTERNAL_STORAGE = "internal"
internal const val EXTERNAL_LIBRARY_LIMIT = 50L * 1024 * 1024 * 1024
internal data class MediaVolume(
    val id: String, val name: String, val directory: File?, val removable: Boolean = false,
    val mounted: Boolean = directory != null, val writable: Boolean = directory?.canWrite() == true,
    val totalBytes: Long = directory?.totalSpace ?: 0, val freeBytes: Long = directory?.usableSpace ?: 0,
)
internal class StorageUnavailable(val code: String) : IllegalStateException(code)
internal data class StorageCommand(val key: String, val fingerprint: String)
internal data class RetainedOriginal(val id: String, val storageId: String, val tracks: List<LocalTrack>)
internal data class StagedMediaCopy(val id:String,val storageId:String,val track:LocalTrack)
internal data class StorageMigration(
    val status: String = "IDLE", val targetId: String = "", val totalFiles: Int = 0,
    val completedFiles: Int = 0, val copiedBytes: Long = 0, val totalBytes: Long = 0, val error: String = "",
)

/** The catalogue and routing journal always remain internal. No request can provide a filesystem path. */
internal class PreparedStorage(
    private val internalDirectory: File,
    private val externalVolumes: () -> List<MediaVolume> = { emptyList() },
    private val internalLimit: Long = LIBRARY_LIMIT,
) {
    private val journal = File(internalDirectory, "storage.json")
    private var selected = INTERNAL_STORAGE
    private var version = 0L
    private var placements = emptyMap<String, String>()
    private var names = emptyMap<String, String>()
    private var originals = emptyList<RetainedOriginal>()
    private var migration = StorageMigration()
    private var invalid = false
    private var receipts = emptyMap<String,String>()
    private var staged = emptyList<StagedMediaCopy>()
    init {
        if (journal.exists()) try {
            val j = JSONObject(journal.readText()); require(j.getInt("format") == 1)
            selected = j.getString("selectedId"); version = j.getLong("version"); require(version >= 0)
            val p = j.getJSONObject("placements")
            placements = p.keys().asSequence().associateWith { id -> require(MEDIA_ID.matches(id)); p.getString(id) }
            val n = j.optJSONObject("names") ?: JSONObject()
            names = n.keys().asSequence().associateWith { n.getString(it).take(100) }
            val receiptsJson=j.optJSONObject("receipts") ?: JSONObject()
            require(receiptsJson.length()<=1_000)
            receipts=receiptsJson.keys().asSequence().associateWith { receiptsJson.getString(it) }
            require((placements.values + selected + names.keys).all(::validStorageId))
            val a = j.optJSONArray("originals") ?: JSONArray()
            originals = List(a.length()) { i -> val o = a.getJSONObject(i); val id = o.getString("id"); require(MEDIA_ID.matches(id))
                val target = o.getString("storageId"); require(validStorageId(target))
                RetainedOriginal(id, target, readTracks(id, o.getJSONArray("tracks"))) }
            val stages=j.optJSONArray("staged") ?: JSONArray()
            staged=List(stages.length()) { i -> val s=stages.getJSONObject(i);val id=s.getString("id");require(MEDIA_ID.matches(id));val target=s.getString("storageId");require(validStorageId(target));StagedMediaCopy(id,target,readTracks(id,s.getJSONArray("tracks")).single()) }
            j.optJSONObject("migration")?.let { m -> migration = StorageMigration(m.getString("status"),m.optString("targetId"),m.optInt("totalFiles"),m.optInt("completedFiles"),m.optLong("copiedBytes"),m.optLong("totalBytes"),m.optString("error")) }
            if (migration.status in setOf("COPYING","VERIFYING","CANCELLING","CLEANING")) migration = migration.copy(status = "INTERRUPTED", error = "TRANSFER_INTERRUPTED")
        } catch (_: Exception) { invalid = true }
    }
    @Synchronized fun selectedId() = selected
    @Synchronized fun version() = version
    @Synchronized fun placement(id: String): String { require(MEDIA_ID.matches(id)); return placements[id] ?: INTERNAL_STORAGE }
    @Synchronized fun migration() = migration
    @Synchronized fun retained() = originals.toList()
    @Synchronized fun healthy() = !invalid
    @Synchronized fun knownIds() = placements.keys.toSet()
    @Synchronized fun stagedCopies() = staged.toList()
    @Synchronized fun stage(copy:StagedMediaCopy) { commit(staging=staged.filterNot { it.id==copy.id && it.storageId==copy.storageId && it.track.name==copy.track.name }+copy) }
    @Synchronized fun forgetStage(copy:StagedMediaCopy) { commit(staging=staged-copy) }
    @Synchronized fun durablePlacement(id:String):String {
        if(!journal.isFile) return INTERNAL_STORAGE
        val j=JSONObject(journal.readText());check(j.getLong("version")==version) { "STORAGE_CHANGED" }
        return j.getJSONObject("placements").optString(id,INTERNAL_STORAGE)
    }
    @Synchronized fun replay(command:StorageCommand):Boolean {
        val previous=receipts[command.key] ?: return false
        check(previous==command.fingerprint) { "IDEMPOTENCY_KEY_REUSED" };return true
    }
    private fun validStorageId(id: String) = id == INTERNAL_STORAGE || id.matches(Regex("sd:[A-Za-z0-9_-]{1,80}"))
    fun volumes(): List<MediaVolume> {
        val present = externalVolumes().filter { validStorageId(it.id) && it.id != INTERNAL_STORAGE && it.removable }.distinctBy { it.id }
        val remembered = synchronized(this) { names + (placements.values + selected + originals.map { it.storageId } + staged.map { it.storageId }).filter { it != INTERNAL_STORAGE }.associateWith { names[it] ?: "microSD / USB" } }
        return listOf(MediaVolume(INTERNAL_STORAGE,"Память приставки",internalDirectory)) + present +
            remembered.filterKeys { id -> id!=INTERNAL_STORAGE && present.none { it.id == id } }.map { (id,name) -> MediaVolume(id,name,null,true,false,false) }
    }
    fun volume(id: String) = if(id == INTERNAL_STORAGE) MediaVolume(INTERNAL_STORAGE,"Память приставки",internalDirectory)
        else volumes().firstOrNull { it.id == id } ?: throw StorageUnavailable("STORAGE_NOT_FOUND")
    fun limit(id: String): Long = if (id == INTERNAL_STORAGE) internalLimit else volume(id).totalBytes.let {
        if (it <= FREE_RESERVE) 0 else minOf(EXTERNAL_LIBRARY_LIMIT, it - FREE_RESERVE)
    }
    fun root(storageId: String, write: Boolean = false): File {
        if (!healthy()) throw StorageUnavailable("STORAGE_CATALOGUE_ERROR")
        return checkedRoot(volume(storageId),write)
    }
    private fun checkedRoot(v:MediaVolume,write:Boolean):File {
        if (!v.mounted || v.directory == null || !v.directory.isDirectory) throw StorageUnavailable("STORAGE_UNAVAILABLE")
        if (write && !v.writable) throw StorageUnavailable("STORAGE_READ_ONLY")
        return v.directory
    }
    fun rootFor(id: String, write: Boolean = false) = root(placement(id), write)
    fun available(id: String): Boolean = runCatching { rootFor(id); true }.getOrDefault(false)
    fun usedBytes(storageId: String): Long = runCatching { mediaBytes(root(storageId)) }.getOrDefault(0)
    fun allDirectories(): List<File> = volumes().filter { it.mounted && it.directory?.isDirectory == true }.mapNotNull { it.directory }
    @Synchronized fun checkVersion(expected: Long) { check(!invalid) { "STORAGE_CATALOGUE_ERROR" }; check(version == expected) { "STORAGE_CHANGED" } }
    @Synchronized fun select(id: String, expected: Long, command:StorageCommand?=null) {
        checkVersion(expected); require(validStorageId(id)); root(id, true)
        check(migration.status !in setOf("COPYING","VERIFYING","CANCELLING","CLEANING")) { "BUSY" }
        val nextNames = names + (id to volume(id).name)
        commit(selectedId = id, known = nextNames, command=command)
    }
    /** Empty pending items can move on an explicit ENABLE; retained partial files are never abandoned. */
    @Synchronized fun claim(ids: Collection<String>, expectedId: String? = null, expectedVersion: Long? = null) {
        check(!invalid) { "STORAGE_CATALOGUE_ERROR" }
        if (expectedId != null) check(expectedId == selected) { "STORAGE_CHANGED" }
        if (expectedVersion != null) checkVersion(expectedVersion)
        val target = expectedId ?: selected
        // One framework volume enumeration per batch, rather than one Binder traversal per episode.
        val observed=volumes().associateBy { it.id }
        fun observedRoot(id:String,write:Boolean=false)=checkedRoot(observed[id] ?: throw StorageUnavailable("STORAGE_NOT_FOUND"),write)
        if(expectedId!=null || ids.isEmpty())observedRoot(target,true)
        val listings=mutableMapOf<String,Set<String>?>()
        var changed = placements
        ids.forEach { id ->
            require(MEDIA_ID.matches(id))
            val old = placements[id] ?: INTERNAL_STORAGE
            val oldRoot = runCatching { observedRoot(old) }.getOrNull()
            val listed=listings.getOrPut(old) { oldRoot?.list()?.toSet() }
            val retained = oldRoot == null || listed==null || files(oldRoot,id,old!=INTERNAL_STORAGE).any { it.name in listed }
            if (id !in placements && !retained || expectedId != null && !retained) {
                val targetRoot=observedRoot(target,true)
                if(target!=old && (files(targetRoot,id,target!=INTERNAL_STORAGE).any { it.exists() } || staged.any { it.id==id && it.storageId==target }))
                    throw StorageUnavailable("STORAGE_DESTINATION_CONFLICT")
                changed = changed + (id to target)
            } else observedRoot(old,true)
        }
        if (changed != placements) commit(routes = changed, known = names + (target to observed.getValue(target).name))
    }
    @Synchronized fun beginMigration(target: String, entries: List<PreparedEntry>, expected: Long, command:StorageCommand?=null) {
        checkVersion(expected); root(target, true)
        check(migration.status !in setOf("COPYING","VERIFYING","CANCELLING","CLEANING")) { "BUSY" }
        commit(nextMigration = StorageMigration("COPYING", target, entries.size, totalBytes = entries.sumOf { e -> e.tracks.sumOf { it.bytes } }), known = names + (target to volume(target).name), command=command)
    }
    @Synchronized fun beginCleanup(expected:Long,command:StorageCommand?=null) {
        checkVersion(expected);check(migration.status !in setOf("COPYING","VERIFYING","CANCELLING","CLEANING")) { "BUSY" }
        commit(nextMigration=StorageMigration("CLEANING",totalFiles=originals.size,totalBytes=originals.sumOf { o->o.tracks.sumOf { it.bytes } }),command=command)
    }
    @Synchronized fun cancel(expected:Long,command:StorageCommand?=null) {
        checkVersion(expected);check(migration.status in setOf("COPYING","VERIFYING")) { "BUSY" }
        commit(nextMigration=migration.copy(status="CANCELLING"),command=command)
    }
    @Synchronized fun progress(status: String, bytes: Long) { if(migration.status!="CANCELLING")migration = migration.copy(status = status, copiedBytes = bytes) }
    @Synchronized fun switchCopy(entry: PreparedEntry, source: String, target: String, copied: Long) {
        check(placement(entry.id) == source) { "STORAGE_CHANGED" }
        val retained = originals.filterNot { it.id == entry.id && it.storageId in setOf(source,target) } + RetainedOriginal(entry.id, source, entry.tracks)
        commit(routes = placements + (entry.id to target), backups = retained, staging=staged.filterNot { it.id==entry.id && it.storageId==target },
            nextMigration = migration.copy(status="COPYING",completedFiles=migration.completedFiles+1,copiedBytes=copied))
    }
    @Synchronized fun finish(status: String, error: String = "") { commit(nextMigration=migration.copy(status=status,error=error)) }
    @Synchronized fun removeOriginal(original: RetainedOriginal) { commit(backups = originals - original) }
    @Synchronized private fun commit(selectedId: String = selected, routes: Map<String,String> = placements,
        known: Map<String,String> = names, backups: List<RetainedOriginal> = originals, nextMigration: StorageMigration = migration, command:StorageCommand?=null, staging:List<StagedMediaCopy> = staged) {
        check(!invalid) { "STORAGE_CATALOGUE_ERROR" }
        val nextReceipts=if(command==null) receipts else {
            require(command.key.length<=500 && command.fingerprint.length<=1_000)
            check(receipts.size<1_000 || command.key in receipts) { "STORAGE_COMMAND_LIMIT" }
            receipts + (command.key to command.fingerprint)
        }
        val m=nextMigration
        val j=JSONObject().put("format",1).put("version",version+1).put("selectedId",selectedId)
            .put("placements",JSONObject(routes)).put("names",JSONObject(known)).put("receipts",JSONObject(nextReceipts)).put("originals",JSONArray().apply { backups.forEach { o -> put(JSONObject().put("id",o.id).put("storageId",o.storageId).put("tracks",tracksJson(o.tracks))) } })
            .put("staged",JSONArray().apply { staging.forEach { o -> put(JSONObject().put("id",o.id).put("storageId",o.storageId).put("tracks",tracksJson(listOf(o.track)))) } })
            .put("migration",JSONObject().put("status",m.status).put("targetId",m.targetId).put("totalFiles",m.totalFiles).put("completedFiles",m.completedFiles).put("copiedBytes",m.copiedBytes).put("totalBytes",m.totalBytes).put("error",m.error))
        val tmp=File(journal.path+".tmp")
        FileOutputStream(tmp).use { it.write(j.toString().toByteArray());it.fd.sync() }
        check(tmp.renameTo(journal)) { "STORAGE_CATALOGUE_ERROR" }
        selected=selectedId;placements=routes;names=known;originals=backups;migration=nextMigration;receipts=nextReceipts;staged=staging;version++
    }
    companion object {
        fun mediaBytes(root:File):Long = root.listFiles().orEmpty().sumOf { file ->
            if(isMediaFile(file))file.length() else if(file.name=="stream-cache" && file.isDirectory && file.canonicalFile.parentFile==root.canonicalFile)
                file.walkTopDown().filter { it.isFile && it.canonicalPath.startsWith(file.canonicalPath+File.separator) }.sumOf { it.length() } else 0L
        }
        fun isMediaFile(file: File) = file.isFile && (file.name.endsWith(".mp4") || file.name.endsWith(".part") || file.name.endsWith(".copying"))
        fun physicalName(id:String,name:String,external:Boolean):String {
            require(MEDIA_ID.matches(id) && name in listOf("$id.video.mp4","$id.audio.mp4"))
            if(!external)return name
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
            return hash+name.removePrefix(id)
        }
        fun safeFile(root:File,name:String):File = File(root,name).also { check(it.canonicalFile.parentFile==root.canonicalFile) { "STORAGE_PATH_INVALID" } }
        fun files(root:File,id:String,external:Boolean=false):List<File> = listOf("$id.video.mp4","$id.audio.mp4").flatMap { name -> val n=physicalName(id,name,external);listOf(safeFile(root,n),safeFile(root,"$n.part"),safeFile(root,"$n.stream.part"),safeFile(root,"$n.resume.json"),safeFile(root,"$n.resume.json.tmp")) }
        private fun tracksJson(tracks:List<LocalTrack>)=JSONArray().apply { tracks.forEach { put(JSONObject().put("name",it.name).put("bytes",it.bytes).put("sha256",it.sha256)) } }
        private fun readTracks(id:String,a:JSONArray)=List(a.length()) { i -> val t=a.getJSONObject(i);val name=t.getString("name");require(name in listOf("$id.video.mp4","$id.audio.mp4"));LocalTrack(name,t.getLong("bytes"),t.getString("sha256")) }
    }
}

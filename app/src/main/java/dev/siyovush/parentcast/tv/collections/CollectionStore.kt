package dev.siyovush.parentcast.tv.collections

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A fixed selection. No source URL or playlist subscription is stored. */
data class SavedCollection(
    val id: String,
    val name: String,
    val videoIds: List<String>,
    val createdAtMs: Long,
    val creationFingerprint: String? = null,
    val titles: Map<String,String> = emptyMap(),
) {
    init {
        require(Regex("[A-Za-z0-9_-]{1,100}").matches(id)) { "Invalid collection ID" }
        require(name.isNotBlank() && name.length <= 100 && name.none(Character::isISOControl)) {
            "Invalid collection name"
        }
        require(videoIds.size in 1..500) { "A collection must contain 1..500 videos" }
        require(videoIds.all(dev.siyovush.parentcast.tv.media.OnlineMediaIds::isOnline)) { "Invalid video ID" }
        require(titles.keys.all { it in videoIds } && titles.values.all { it == dev.siyovush.parentcast.tv.queue.cleanVideoTitle(it) })
        require(createdAtMs >= 0) { "Invalid creation time" }
        require(creationFingerprint == null || Regex("[0-9a-f]{64}").matches(creationFingerprint)) {
            "Invalid creation fingerprint"
        }
    }
}

interface CollectionStore {
    fun list(): List<SavedCollection>
    fun get(id: String): SavedCollection?
    fun save(collection: SavedCollection)
    fun delete(id: String): Boolean
}

class CollectionLimitException : IllegalStateException("Saved collection storage limit reached")

/** The caller places this file in Context.noBackupFilesDir. */
class AtomicCollectionStore(private val file: File) : CollectionStore {
    @Synchronized
    override fun list(): List<SavedCollection> = readCollections()

    @Synchronized
    override fun get(id: String): SavedCollection? = readCollections().firstOrNull { it.id == id }

    @Synchronized
    override fun save(collection: SavedCollection) {
        // Copy before validation/use; later caller mutations cannot change the saved selection.
        val snapshot = collection.copy(videoIds = collection.videoIds.toList(), titles = collection.titles.toMap())
        val current = readCollections()
        val existingIndex = current.indexOfFirst { it.id == snapshot.id }
        val updated = current.toMutableList().apply {
            if (existingIndex >= 0) set(existingIndex, snapshot) else add(snapshot)
        }
        if (updated.size > MAX_COLLECTIONS || updated.sumOf { it.videoIds.size } > MAX_TOTAL_VIDEOS) {
            throw CollectionLimitException()
        }
        writeCollections(updated)
    }

    @Synchronized
    override fun delete(id: String): Boolean {
        val current = readCollections()
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) return false
        writeCollections(updated)
        return true
    }

    private fun readCollections(): List<SavedCollection> {
        if (!file.exists()) return emptyList()
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) {
            throw IOException("Invalid saved collection storage")
        }
        try {
            val root = JSONObject(String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
            require(root.getInt("version") == 1)
            val records = root.getJSONArray("collections")
            require(records.length() <= MAX_COLLECTIONS)
            val collections = List(records.length()) { index ->
                val record = records.getJSONObject(index)
                val ids = record.getJSONArray("videoIds")
                require(ids.length() in 1..500)
                SavedCollection(
                    id = record.get("id") as String,
                    name = record.get("name") as String,
                    videoIds = List(ids.length()) { ids.get(it) as String },
                    createdAtMs = record.getLong("createdAtMs"),
                    titles = record.optJSONObject("titles")?.let { t -> t.keys().asSequence().associateWith { t.getString(it) } }.orEmpty(),
                    creationFingerprint = if (record.has("creationFingerprint") && !record.isNull("creationFingerprint")) {
                        record.get("creationFingerprint") as String
                    } else null,
                )
            }
            require(collections.map { it.id }.distinct().size == collections.size)
            require(collections.sumOf { it.videoIds.size } <= MAX_TOTAL_VIDEOS)
            return collections
        } catch (error: Exception) {
            throw IOException("Invalid saved collection storage", error)
        }
    }

    private fun writeCollections(collections: List<SavedCollection>) {
        val records = JSONArray()
        collections.forEach { collection ->
            records.put(
                JSONObject()
                    .put("id", collection.id)
                    .put("name", collection.name)
                    .put("videoIds", JSONArray(collection.videoIds)).put("titles",JSONObject(collection.titles))
                    .put("createdAtMs", collection.createdAtMs)
                    .put("creationFingerprint", collection.creationFingerprint ?: JSONObject.NULL),
            )
        }
        val bytes = JSONObject().put("version", 1).put("collections", records)
            .toString().toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) throw CollectionLimitException()
        val target = file.toPath().toAbsolutePath()
        val parent = target.parent ?: throw IOException("Collection file has no parent")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, file.name, ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        const val MAX_COLLECTIONS = 50
        const val MAX_TOTAL_VIDEOS = 10_000
        private const val MAX_FILE_BYTES = 1_048_576L
    }
}

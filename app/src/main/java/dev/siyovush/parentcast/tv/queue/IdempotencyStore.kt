package dev.siyovush.parentcast.tv.queue

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class StoredResponse(
    val code: Int,
    val body: String,
    val createdAtMs: Long,
    val requestFingerprint: String? = null,
)

interface IdempotencyStore {
    fun get(key: String): StoredResponse?
    fun put(key: String, response: StoredResponse)
}

class AtomicIdempotencyStore(
    private val file: File,
    private val ttlMs: Long = 86_400_000L,
    private val maxEntries: Int = 100,
) : IdempotencyStore {
    @Synchronized
    override fun get(key: String): StoredResponse? {
        val now = System.currentTimeMillis()
        val records = readRecords(now)
        return records[key]
    }

    @Synchronized
    override fun put(key: String, response: StoredResponse) {
        val now = System.currentTimeMillis()
        val records = readRecords(now).toMutableMap()
        records[key] = response
        val trimmed = records.entries
            .sortedBy { it.value.createdAtMs }
            .takeLast(maxEntries)
            .associate { it.key to it.value }
        writeRecords(trimmed)
    }

    private fun readRecords(now: Long): Map<String, StoredResponse> {
        if (!file.isFile || file.length() == 0L) return emptyMap()
        val root = JSONObject(String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
        return buildMap {
            root.keys().forEach { key ->
                val item = root.getJSONObject(key)
                val createdAt = item.getLong("createdAtMs")
                if (now - createdAt <= ttlMs) {
                    put(
                        key,
                        StoredResponse(
                            code = item.getInt("code"),
                            body = item.getString("body"),
                            createdAtMs = createdAt,
                            requestFingerprint = item.optString("requestFingerprint").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }
    }

    private fun writeRecords(records: Map<String, StoredResponse>) {
        val root = JSONObject()
        records.forEach { (key, response) ->
            root.put(
                key,
                JSONObject()
                    .put("code", response.code)
                    .put("body", response.body)
                    .put("createdAtMs", response.createdAtMs)
                    .put("requestFingerprint", response.requestFingerprint ?: JSONObject.NULL),
            )
        }
        val target = file.toPath().toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, file.name, ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(root.toString().toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

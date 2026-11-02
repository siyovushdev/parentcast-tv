package dev.siyovush.parentcast.tv.web

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class DeviceRecord(
    val deviceId: String,
    val label: String,
    val createdAtMs: Long,
    val revokedAtMs: Long = 0,
)

interface TokenStore {
    fun create(label: String): Pair<DeviceRecord, String>
    fun verify(token: String): DeviceRecord?
    fun revoke(deviceId: String): Boolean
    fun list(): List<DeviceRecord>
}

class AtomicTokenStore(private val file: File) : TokenStore {
    private val random = SecureRandom()

    @Synchronized
    override fun create(label: String): Pair<DeviceRecord, String> {
        val token = ByteArray(32).also(random::nextBytes).toBase64()
        val record = DeviceRecord(
            deviceId = ByteArray(8).also(random::nextBytes).toBase64(),
            label = label.ifBlank { "Browser" }.take(40),
            createdAtMs = System.currentTimeMillis(),
        )
        val entries = readEntries() + TokenEntry(record, sha256(token))
        writeEntries(entries)
        return record to token
    }

    @Synchronized
    override fun verify(token: String): DeviceRecord? {
        val hash = sha256(token)
        return readEntries().firstOrNull { entry ->
            entry.record.revokedAtMs == 0L && MessageDigest.isEqual(entry.tokenHash, hash)
        }?.record
    }

    @Synchronized
    override fun revoke(deviceId: String): Boolean {
        val entries = readEntries()
        val index = entries.indexOfFirst { it.record.deviceId == deviceId && it.record.revokedAtMs == 0L }
        if (index < 0) return false
        val updated = entries.toMutableList()
        updated[index] = updated[index].copy(
            record = updated[index].record.copy(revokedAtMs = System.currentTimeMillis()),
        )
        writeEntries(updated)
        return true
    }

    @Synchronized
    override fun list(): List<DeviceRecord> = readEntries().map { it.record }

    private fun readEntries(): List<TokenEntry> {
        if (!file.isFile || file.length() == 0L) return emptyList()
        val array = JSONArray(String(file.readBytes(), Charsets.UTF_8))
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    TokenEntry(
                        record = DeviceRecord(
                            deviceId = item.getString("deviceId"),
                            label = item.getString("label"),
                            createdAtMs = item.getLong("createdAtMs"),
                            revokedAtMs = item.optLong("revokedAtMs", 0),
                        ),
                        tokenHash = item.getString("tokenHash").decodeBase64(),
                    ),
                )
            }
        }
    }

    private fun writeEntries(entries: List<TokenEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("deviceId", entry.record.deviceId)
                    .put("label", entry.record.label)
                    .put("createdAtMs", entry.record.createdAtMs)
                    .put("revokedAtMs", entry.record.revokedAtMs)
                    .put("tokenHash", entry.tokenHash.toBase64()),
            )
        }
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(array.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IllegalStateException("Could not store device token")
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        } finally {
            temporary.delete()
        }
    }

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))

    private data class TokenEntry(val record: DeviceRecord, val tokenHash: ByteArray)
}

private fun ByteArray.toBase64(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun String.decodeBase64(): ByteArray = Base64.getUrlDecoder().decode(this)

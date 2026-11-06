package dev.siyovush.parentcast.tv.queue

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface QueueStore {
    fun read(): QueueState
    fun write(state: QueueState)
}

object QueueSnapshotCodec {
    private const val VERSION = 4

    fun encode(state: QueueState): String {
        val items = JSONArray()
        state.items.forEach { item ->
            items.put(
                JSONObject()
                    .put("queueItemId", item.queueItemId)
                    .put("videoId", item.videoId).put("local", item.local).put("title", item.title),
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("items", items)
            .put("groups", JSONArray().apply { state.groups.forEach { g -> put(ProgrammeCodec.group(g)) } })
            .put("programme", ProgrammeCodec.encode(state))
            .put("activeGroupId", state.activeGroupId ?: JSONObject.NULL)
            .put("currentIndex", state.currentIndex)
            .put("positionMs", state.positionMs)
            .put("intent", state.intent.name)
            .put("finished", state.finished)
            .put("queueVersion", state.version)
            .put("batchReceipts", JSONArray().apply {
                state.batchReceipts.forEach { receipt ->
                    put(JSONObject().put("key", receipt.key).put("fingerprint", receipt.fingerprint)
                        .put("createdAtMs", receipt.createdAtMs).apply {
                            receipt.addition?.let { put("addition", JSONObject().put("added", it.added).put("skipped", it.skipped).put("groupId", it.groupId ?: JSONObject.NULL)) }
                        })
                }
            })
            .toString()
    }

    fun decode(serialized: String): QueueState {
        val root = JSONObject(serialized)
        if (root.getInt("version") !in 1..VERSION) {
            throw JSONException("Unsupported queue snapshot version")
        }
        val serializedItems = root.getJSONArray("items")
        val items = buildList {
            for (index in 0 until serializedItems.length()) {
                val item = serializedItems.getJSONObject(index)
                add(
                    QueueItem(
                        queueItemId = item.getString("queueItemId"),
                        videoId = item.getString("videoId"),
                        local = item.optBoolean("local", false),
                        title = item.optString("title", ""),
                    ),
                )
            }
        }
        return QueueState(
            items = items,
            groups = root.optJSONArray("groups")?.let { a -> List(a.length()) { n -> ProgrammeCodec.readGroup(a.getJSONObject(n)) } }.orEmpty(),
            activeGroupId = root.optString("activeGroupId").takeUnless { it.isBlank() || it == "null" },
            currentIndex = root.getInt("currentIndex"),
            positionMs = root.getLong("positionMs"),
            intent = PlaybackIntent.valueOf(root.getString("intent")),
            finished = root.getBoolean("finished"),
            version = root.optLong("queueVersion", 0),
            batchReceipts = root.optJSONArray("batchReceipts")?.let { receipts ->
                List(receipts.length()) { index ->
                    val receipt = receipts.getJSONObject(index)
                    BatchReceipt(receipt.getString("key"), receipt.getString("fingerprint"), receipt.getLong("createdAtMs"),
                        receipt.optJSONObject("addition")?.let { QueueAddition(it.getInt("added"), it.getInt("skipped"), it.optString("groupId").takeUnless { v -> v.isBlank() || v=="null" }) })
                }
            } ?: emptyList(),
        ).let { ProgrammeCodec.decodeInto(it, root.optJSONObject("programme")) }
    }
}

class AtomicQueueStore(private val file: File) : QueueStore {
    @Synchronized
    override fun read(): QueueState {
        if (!file.isFile) return QueueState.Empty
        val serialized = String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
        return QueueSnapshotCodec.decode(serialized)
    }

    @Synchronized
    override fun write(state: QueueState) {
        val target = file.toPath().toAbsolutePath()
        val parent = target.parent ?: throw IllegalArgumentException("Queue file has no parent")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, file.name, ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(QueueSnapshotCodec.encode(state).toByteArray(StandardCharsets.UTF_8))
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

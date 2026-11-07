package dev.siyovush.parentcast.tv.web

import dev.siyovush.parentcast.tv.queue.BatchAddResult
import dev.siyovush.parentcast.tv.queue.QueueAddition
import org.json.JSONObject

internal fun additionJson(value: QueueAddition?) = value?.let {
    JSONObject().put("added", it.added).put("skipped", it.skipped).apply { it.groupId?.let { id -> put("groupId",id) } }
}

internal fun queueAdditionResponse(result: BatchAddResult, addition: QueueAddition?, stateBody: String): ApiResponse = when (result) {
    BatchAddResult.APPLIED, BatchAddResult.REPLAY -> ApiResponse(200, JSONObject(stateBody).apply {
        additionJson(addition)?.let { put("addition", it) }
    }.toString())
    BatchAddResult.CONFLICT -> ApiResponse(409, "{\"error\":\"QUEUE_VERSION_CONFLICT\"}")
    BatchAddResult.KEY_REUSED -> ApiResponse(409, "{\"error\":\"IDEMPOTENCY_KEY_REUSED\"}")
    BatchAddResult.RECEIPT_LIMIT -> ApiResponse(429, "{\"error\":\"COMMAND_STORAGE_LIMIT\"}")
    BatchAddResult.STORAGE_ERROR -> ApiResponse(503, "{\"error\":\"QUEUE_STORAGE_ERROR\"}")
}

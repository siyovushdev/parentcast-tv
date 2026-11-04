package dev.siyovush.parentcast.tv.queue

data class QueueItem(
    val queueItemId: String,
    val videoId: String,
    val local: Boolean = false,
    val title: String = "",
) {
    init {
        require(title.length <= 500 && title.none(Character::isISOControl))
        require(queueItemId.isNotBlank())
        require(if (local) Regex("local_[0-9a-f]{32}").matches(videoId) else dev.siyovush.parentcast.tv.media.OnlineMediaIds.isOnline(videoId))
    }

}

enum class PlaybackIntent {
    PLAY,
    PAUSE,
    STOP,
}

data class QueueAddition(val added: Int, val skipped: Int, val groupId: String? = null)
data class BatchReceipt(val key: String, val fingerprint: String, val createdAtMs: Long, val addition: QueueAddition? = null)

enum class BatchAddResult { APPLIED, REPLAY, CONFLICT, KEY_REUSED, STORAGE_ERROR, RECEIPT_LIMIT }

data class QueueState(
    val items: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0,
    val intent: PlaybackIntent = PlaybackIntent.STOP,
    val finished: Boolean = false,
    val version: Long = 0,
    val batchReceipts: List<BatchReceipt> = emptyList(),
    val groups: List<QueueGroup> = emptyList(),
    val activeGroupId: String? = null,
) {
    init {
        require(items.map(QueueItem::queueItemId).distinct().size == items.size)
        if (items.isEmpty()) {
            require(currentIndex == -1)
            require(positionMs == 0L)
            require(!finished)
        } else {
            require(currentIndex in items.indices)
        }
        require(positionMs >= 0L)
        require(groups.size <= 100 && groups.map { it.id }.distinct().size == groups.size)
        val ids = items.map { it.videoId }.toSet()
        require(groups.all { ids.containsAll(it.videoIds) })
        require(activeGroupId == null || groups.any { it.id == activeGroupId && currentItem?.videoId in it.videoIds })
    }

    val currentItem: QueueItem?
        get() = items.getOrNull(currentIndex)

    val pendingItems: List<QueueItem>
        get() = if (currentIndex < 0) emptyList() else items.drop(currentIndex + 1)

    val activeGroup: QueueGroup? get() = groups.find { it.id == activeGroupId }
    fun remainingItems(): List<QueueItem> {
        val group = activeGroup ?: return items.drop(currentIndex.coerceAtLeast(0))
        val ids = group.videoIds.drop(group.videoIds.indexOf(currentItem?.videoId).coerceAtLeast(0))
        val byId = items.associateBy { it.videoId }
        return ids.mapNotNull(byId::get)
    }
    private fun retain(retained: List<QueueItem>): QueueState {
        val ids = retained.map { it.videoId }.toSet()
        val kept = groups.mapNotNull { g -> g.videoIds.filter { it in ids }.takeIf { it.isNotEmpty() }?.let { g.copy(videoIds = it) } }
        return copy(items = retained, groups = kept, activeGroupId = activeGroupId?.takeIf { id -> kept.any { it.id == id } })
    }

    fun add(item: QueueItem): QueueState = if (items.any { it.videoId == item.videoId }) this else copy(
        items = items + item,
        currentIndex = if (items.isEmpty()) 0 else currentIndex,
    )

    fun playNow(item: QueueItem): QueueState {
        val existing = items.indexOfFirst { it.videoId == item.videoId }
        if (existing >= 0) return copy(currentIndex = existing, positionMs = 0, activeGroupId = null,
            intent = PlaybackIntent.PLAY, finished = false)
        if (items.isEmpty()) {
            return QueueState(items = listOf(item), currentIndex = 0, intent = PlaybackIntent.PLAY)
        }
        if (finished) {
            return copy(
                items = items + item, activeGroupId = null,
                currentIndex = items.size,
                positionMs = 0,
                intent = PlaybackIntent.PLAY,
                finished = false,
            )
        }
        return copy(
            items = items.take(currentIndex + 1) + item + items.drop(currentIndex + 1), activeGroupId = null,
            currentIndex = currentIndex + 1,
            positionMs = 0,
            intent = PlaybackIntent.PLAY,
            finished = false,
        )
    }

    fun play(): QueueState {
        if (items.isEmpty()) return this
        if (finished && activeGroup != null) return copy(currentIndex = items.indexOfFirst { it.videoId == activeGroup!!.videoIds.first() },
            positionMs = 0, intent = PlaybackIntent.PLAY, finished = false)
        if (finished && currentIndex < items.lastIndex) {
            return copy(
                currentIndex = currentIndex + 1,
                positionMs = 0,
                intent = PlaybackIntent.PLAY,
                finished = false,
            )
        }
        return copy(
            positionMs = if (finished) 0 else positionMs,
            intent = PlaybackIntent.PLAY,
            finished = false,
        )
    }

    fun togglePause(): QueueState = if (intent == PlaybackIntent.PLAY) {
        copy(intent = PlaybackIntent.PAUSE)
    } else {
        play()
    }

    fun stop(): QueueState = copy(intent = PlaybackIntent.STOP)

    fun next(): QueueState {
        if (items.isEmpty()) return this
        activeGroup?.let { group ->
            val nextId = group.videoIds.getOrNull(group.videoIds.indexOf(currentItem!!.videoId) + 1)
            return if (nextId == null) copy(positionMs = 0, intent = PlaybackIntent.STOP, finished = true)
                else copy(currentIndex = items.indexOfFirst { it.videoId == nextId }, positionMs = 0, finished = false)
        }
        if (currentIndex < items.lastIndex) {
            return copy(
                currentIndex = currentIndex + 1,
                positionMs = 0,
                finished = false,
            )
        }
        return copy(
            positionMs = 0,
            intent = PlaybackIntent.STOP,
            finished = true,
        )
    }

    fun deletePending(queueItemId: String): QueueState {
        val index = items.indexOfFirst { it.queueItemId == queueItemId }
        if (index <= currentIndex) return this
        return retain(items.filterNot { it.queueItemId == queueItemId })
    }

    fun movePending(queueItemId: String, moveUp: Boolean): QueueState {
        val index = items.indexOfFirst { it.queueItemId == queueItemId }
        val otherIndex = if (moveUp) index - 1 else index + 1
        if (index <= currentIndex || otherIndex <= currentIndex || otherIndex >= items.size) {
            return this
        }
        return copy(
            items = items.toMutableList().apply {
                add(otherIndex, removeAt(index))
            },
        )
    }

    fun clearPending(): QueueState = retain(items.take(currentIndex + 1))

    fun withPosition(positionMs: Long): QueueState = copy(
        positionMs = positionMs.coerceAtLeast(0),
    )

    companion object {
        val Empty = QueueState()
    }
}

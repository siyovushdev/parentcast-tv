package dev.siyovush.parentcast.tv.queue

data class QueueItem(
    val queueItemId: String,
    val videoId: String,
    val local: Boolean = false,
) {
    init {
        require(queueItemId.isNotBlank())
        require(if (local) Regex("local_[0-9a-f]{32}").matches(videoId) else VIDEO_ID_PATTERN.matches(videoId))
    }

    companion object {
        private val VIDEO_ID_PATTERN = Regex("[A-Za-z0-9_-]{11}")
    }
}

enum class PlaybackIntent {
    PLAY,
    PAUSE,
    STOP,
}

data class QueueAddition(val added: Int, val skipped: Int)
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
    }

    val currentItem: QueueItem?
        get() = items.getOrNull(currentIndex)

    val pendingItems: List<QueueItem>
        get() = if (currentIndex < 0) emptyList() else items.drop(currentIndex + 1)

    fun add(item: QueueItem): QueueState = if (items.any { it.videoId == item.videoId }) this else copy(
        items = items + item,
        currentIndex = if (items.isEmpty()) 0 else currentIndex,
    )

    fun playNow(item: QueueItem): QueueState {
        val existing = items.indexOfFirst { it.videoId == item.videoId }
        if (existing >= 0) return copy(currentIndex = existing, positionMs = 0,
            intent = PlaybackIntent.PLAY, finished = false)
        if (items.isEmpty()) {
            return QueueState(items = listOf(item), currentIndex = 0, intent = PlaybackIntent.PLAY)
        }
        if (finished) {
            return copy(
                items = items + item,
                currentIndex = items.size,
                positionMs = 0,
                intent = PlaybackIntent.PLAY,
                finished = false,
            )
        }
        return copy(
            items = items.take(currentIndex + 1) + item + items.drop(currentIndex + 1),
            currentIndex = currentIndex + 1,
            positionMs = 0,
            intent = PlaybackIntent.PLAY,
            finished = false,
        )
    }

    fun play(): QueueState {
        if (items.isEmpty()) return this
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
        return copy(items = items.filterNot { it.queueItemId == queueItemId })
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

    fun clearPending(): QueueState = copy(items = items.take(currentIndex + 1))

    fun withPosition(positionMs: Long): QueueState = copy(
        positionMs = positionMs.coerceAtLeast(0),
    )

    companion object {
        val Empty = QueueState()
    }
}

package dev.siyovush.parentcast.tv.queue

data class QueueItem(
    val queueItemId: String,
    val videoId: String,
) {
    init {
        require(queueItemId.isNotBlank())
        require(VIDEO_ID_PATTERN.matches(videoId))
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

data class QueueState(
    val items: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0,
    val intent: PlaybackIntent = PlaybackIntent.STOP,
    val finished: Boolean = false,
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

    fun add(item: QueueItem): QueueState = copy(
        items = items + item,
        currentIndex = if (items.isEmpty()) 0 else currentIndex,
    )

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

    fun withPosition(positionMs: Long): QueueState = copy(
        positionMs = positionMs.coerceAtLeast(0),
    )

    companion object {
        val Empty = QueueState()
    }
}

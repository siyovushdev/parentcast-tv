package dev.siyovush.parentcast.tv.playback

import dev.siyovush.parentcast.tv.queue.BatchAddResult
import dev.siyovush.parentcast.tv.queue.BatchReceipt
import dev.siyovush.parentcast.tv.queue.PlaybackIntent
import dev.siyovush.parentcast.tv.queue.QueueItem
import dev.siyovush.parentcast.tv.queue.QueueState
import dev.siyovush.parentcast.tv.queue.QueueStore
import dev.siyovush.parentcast.tv.youtube.ResolvedVideo
import dev.siyovush.parentcast.tv.youtube.YouTubeSource
import dev.siyovush.parentcast.tv.youtube.YouTubeSourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class PlaybackCoordinator(
    private val source: YouTubeSource,
    private val store: QueueStore,
    private val player: PlaybackPlayer,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val authorizationGate: () -> Boolean = { true },
    private val autoResumeOnStartup: Boolean = true,
) : PlaybackPlayer.Listener {
    private var state = QueueState.Empty
    private var status = PlaybackStatus.IDLE
    private var currentTitle = ""
    private var diagnostics = ""
    private var localPlayback = false
    private var error: PlaybackFailure? = null
    private var storageWarning = false
    private var startupStorageBlocked = false
    private var generation = 0L
    private var extractionJob: Job? = null
    private var preparedItemId: String? = null
    private var refreshAttempted = false
    private var listener: ((CoordinatorSnapshot) -> Unit)? = null
    private var started = false
    private var closed = false
    private var updatedAtMs = System.currentTimeMillis()

    init {
        state = try {
            store.read()
        } catch (error: Exception) {
            storageWarning = true
            QueueState.Empty
        }
    }

    fun setListener(listener: (CoordinatorSnapshot) -> Unit) {
        this.listener = listener
        emit()
    }

    fun start() {
        if (started || closed) return
        started = true
        player.setListener(this)
        val startupState = StartupPlaybackPolicy.restore(state, autoResumeOnStartup)
        if (startupState != state) {
            try {
                // Commit the safe intent before any extraction can be scheduled.
                store.write(startupState)
            } catch (_: Exception) {
                state = startupState
                storageWarning = true
                startupStorageBlocked = true
                status = PlaybackStatus.PAUSED
                emit()
                return
            }
            state = startupState
        }
        if (state.finished) {
            status = PlaybackStatus.ENDED
        } else if (state.currentItem != null) {
            status = PlaybackStatus.STOPPED
        }
        emit()
        if (state.currentItem != null && state.intent != PlaybackIntent.STOP && !state.finished) {
            prepareCurrent()
        }
    }

    fun add(videoId: String) {
        if (closed) return
        state = state.add(QueueItem(UUID.randomUUID().toString(), videoId))
        if (status == PlaybackStatus.IDLE) status = PlaybackStatus.STOPPED
        error = null
        persist()
        emit()
    }

    fun batchReplay(commandKey: String, fingerprint: String): BatchAddResult? {
        val receipt = state.batchReceipts.find { it.key == commandKey && System.currentTimeMillis() - it.createdAtMs <= 86_400_000L }
            ?: return null
        return if (receipt.fingerprint == fingerprint) BatchAddResult.REPLAY else BatchAddResult.KEY_REUSED
    }

    /** One queue transaction; no player calls and no change of playback intent. */
    fun addBatch(videoIds: List<String>, expectedVersion: Long, commandKey: String, requestFingerprint: String? = null): BatchAddResult {
        require(videoIds.size in 1..500 && videoIds.all { Regex("[A-Za-z0-9_-]{11}").matches(it) })
        require(commandKey.length in 8..300)
        if (closed) return BatchAddResult.STORAGE_ERROR
        val fingerprint = requestFingerprint ?: java.security.MessageDigest.getInstance("SHA-256")
            .digest((expectedVersion.toString() + "|" + videoIds.joinToString(",")).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        val receipts = state.batchReceipts.filter { now - it.createdAtMs <= 86_400_000L }
        receipts.find { it.key == commandKey }?.let {
            return if (it.fingerprint == fingerprint) BatchAddResult.REPLAY else BatchAddResult.KEY_REUSED
        }
        if (state.version != expectedVersion) return BatchAddResult.CONFLICT
        if (receipts.size >= 1000) return BatchAddResult.RECEIPT_LIMIT
        val items = videoIds.map { QueueItem(UUID.randomUUID().toString(), it) }
        val updated = state.copy(
            items = state.items + items,
            currentIndex = if (state.items.isEmpty()) 0 else state.currentIndex,
            version = state.version + 1,
            batchReceipts = receipts + BatchReceipt(commandKey, fingerprint, now),
        )
        try {
            store.write(updated)
        } catch (_: Exception) {
            storageWarning = true
            emit()
            return BatchAddResult.STORAGE_ERROR
        }
        state = updated
        if (status == PlaybackStatus.IDLE) status = PlaybackStatus.STOPPED
        emit()
        return BatchAddResult.APPLIED
    }

    fun play() {
        if (closed || state.items.isEmpty()) return
        state = state.play()
        error = null
        refreshAttempted = false
        persist()
        if (!authorizationGate()) {
            status = PlaybackStatus.WAITING_FOR_SESSION
            emit()
            return
        }
        val current = state.currentItem ?: return
        if (preparedItemId == current.queueItemId) {
            status = PlaybackStatus.BUFFERING
            player.play()
            emit()
        } else {
            prepareCurrent()
        }
    }

    fun onSessionChanged() {
        if (closed || startupStorageBlocked) return
        if (!authorizationGate()) {
            if (state.intent != PlaybackIntent.STOP && !state.finished) {
                status = PlaybackStatus.WAITING_FOR_SESSION
                emit()
            }
            return
        }
        if (state.intent == PlaybackIntent.STOP || state.finished) return
        if (preparedItemId == null) prepareCurrent()
    }

    fun pauseResume() {
        if (closed || state.currentItem == null) return
        if (state.intent != PlaybackIntent.PLAY) {
            play()
            return
        }
        savePosition()
        state = state.togglePause()
        player.pause()
        status = PlaybackStatus.PAUSED
        persist()
        emit()
    }

    fun next() {
        if (closed || state.items.isEmpty()) return
        state = state.next()
        currentTitle = ""
        diagnostics = ""
        error = null
        refreshAttempted = false
        persist()
        if (state.currentItem != null && !state.finished && state.intent != PlaybackIntent.STOP) {
            prepareCurrent()
        } else {
            invalidateRequest()
            status = if (state.finished) PlaybackStatus.ENDED else PlaybackStatus.STOPPED
            emit()
        }
    }

    fun stop() {
        if (closed) return
        savePosition()
        state = state.stop()
        currentTitle = ""
        diagnostics = ""
        error = null
        refreshAttempted = false
        invalidateRequest()
        status = PlaybackStatus.STOPPED
        persist()
        emit()
    }

    fun clearAll(expectedVersion: Long): Boolean {
        if (closed || state.version != expectedVersion) return false
        val cleared = QueueState.Empty.copy(version = state.version + 1, batchReceipts = state.batchReceipts)
        store.write(cleared)
        state = cleared
        invalidateRequest()
        currentTitle = ""; diagnostics = ""; error = null
        status = PlaybackStatus.STOPPED
        emit()
        return true
    }

    fun deletePending(queueItemId: String) {
        if (closed) return
        val updated = state.deletePending(queueItemId)
        if (updated == state) return
        state = updated
        persist()
        emit()
    }

    fun playNow(videoId: String) {
        if (closed) return
        state = state.playNow(QueueItem(UUID.randomUUID().toString(), videoId))
        currentTitle = ""
        diagnostics = ""
        error = null
        refreshAttempted = false
        persist()
        if (!authorizationGate()) {
            status = PlaybackStatus.WAITING_FOR_SESSION
            emit()
            return
        }
        prepareCurrent()
    }

    fun movePending(queueItemId: String, moveUp: Boolean) {
        if (closed) return
        val updated = state.movePending(queueItemId, moveUp)
        if (updated == state) return
        state = updated
        persist()
        emit()
    }

    fun clearPending() {
        if (closed) return
        val updated = state.clearPending()
        if (updated == state) return
        state = updated
        persist()
        emit()
    }

    fun savePosition() {
        val current = state.currentItem ?: return
        if (preparedItemId != current.queueItemId || state.finished) return
        state = state.withPosition(player.currentPositionMs())
        store.write(state)
    }

    fun close() {
        if (closed) return
        closed = true
        generation++
        extractionJob?.cancel()
    }

    override fun onEnded(generation: Long) {
        if (!isCurrentPlayerRequest(generation)) return
        state = state.next()
        currentTitle = ""
        diagnostics = ""
        error = null
        refreshAttempted = false
        persist()
        if (state.intent == PlaybackIntent.PLAY && !state.finished) {
            prepareCurrent()
        } else {
            invalidateRequest()
            status = PlaybackStatus.ENDED
            emit()
        }
    }

    override fun onError(generation: Long, failure: PlaybackFailure) {
        if (!isCurrentPlayerRequest(generation)) return
        savePosition()
        if (failure.canRefresh && !refreshAttempted && state.intent != PlaybackIntent.STOP) {
            refreshAttempted = true
            prepareCurrent(preserveRefreshAttempt = true)
            return
        }
        preparedItemId = null
        status = PlaybackStatus.ERROR
        error = failure
        emit()
    }

    override fun onFirstFrame(generation: Long) {
        if (!isCurrentPlayerRequest(generation)) return
        status = if (state.intent == PlaybackIntent.PLAY) {
            PlaybackStatus.PLAYING
        } else {
            PlaybackStatus.PAUSED
        }
        error = null
        emit()
    }

    override fun onPlaying(generation: Long) {
        if (!isCurrentPlayerRequest(generation) || state.intent != PlaybackIntent.PLAY) return
        status = PlaybackStatus.PLAYING
        error = null
        emit()
    }

    override fun onBuffering(generation: Long) {
        if (!isCurrentPlayerRequest(generation) || state.intent != PlaybackIntent.PLAY) return
        status = PlaybackStatus.BUFFERING
        emit()
    }

    override fun onDiagnostics(generation: Long, diagnostics: String) {
        if (!isCurrentPlayerRequest(generation)) return
        this.diagnostics = diagnostics
        emit()
    }

    internal fun handleResolved(
        generation: Long,
        queueItemId: String,
        resolved: ResolvedVideo,
    ) {
        if (!isCurrentExtraction(generation, queueItemId)) return
        try {
            preparedItemId = queueItemId
            currentTitle = resolved.title
            localPlayback = resolved.video.url.startsWith("file:")
            diagnostics = resolved.selectionDescription
            player.prepare(
                generation = generation,
                video = resolved,
                positionMs = state.positionMs,
                playWhenReady = state.intent == PlaybackIntent.PLAY,
            )
            status = if (state.intent == PlaybackIntent.PLAY) {
                PlaybackStatus.BUFFERING
            } else {
                PlaybackStatus.PAUSED
            }
            error = null
        } catch (error: Exception) {
            preparedItemId = null
            status = PlaybackStatus.ERROR
            this.error = PlaybackFailure(PlaybackErrorKind.PLAYBACK)
        }
        emit()
    }

    internal fun handleSourceError(
        generation: Long,
        queueItemId: String,
        cause: Throwable,
    ) {
        if (!isCurrentExtraction(generation, queueItemId)) return
        preparedItemId = null
        val failure = if (cause is YouTubeSourceException) {
            cause.failure
        } else {
            PlaybackFailure(PlaybackErrorKind.EXTRACTOR)
        }
        if (failure.stopRequested) {
            state = state.stop()
            refreshAttempted = false
            persist()
        }
        status = PlaybackStatus.ERROR
        error = failure
        emit()
    }

    private fun prepareCurrent(preserveRefreshAttempt: Boolean = false) {
        val current = state.currentItem ?: return
        if (!authorizationGate()) {
            status = PlaybackStatus.WAITING_FOR_SESSION
            emit()
            return
        }
        if (!preserveRefreshAttempt) refreshAttempted = false
        invalidateRequest()
        val requestGeneration = generation
        status = PlaybackStatus.LOADING
        error = null
        emit()
        extractionJob = scope.launch {
            try {
                val resolved = withContext(ioDispatcher) {
                    source.resolve(current.videoId)
                }
                handleResolved(requestGeneration, current.queueItemId, resolved)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleSourceError(requestGeneration, current.queueItemId, error)
            }
        }
    }

    private fun invalidateRequest() {
        localPlayback = false
        generation++
        extractionJob?.cancel()
        extractionJob = null
        preparedItemId = null
        player.stop()
    }

    private fun isCurrentExtraction(generation: Long, queueItemId: String): Boolean =
        !closed && this.generation == generation && state.currentItem?.queueItemId == queueItemId

    private fun isCurrentPlayerRequest(generation: Long): Boolean {
        val current = state.currentItem ?: return false
        return !closed && this.generation == generation && preparedItemId == current.queueItemId
    }

    private fun persist() {
        state = state.copy(version = state.version + 1)
        store.write(state)
        startupStorageBlocked = false
    }

    private fun emit() {
        updatedAtMs = System.currentTimeMillis()
        listener?.invoke(currentSnapshot())
    }

    fun currentSnapshot(): CoordinatorSnapshot = CoordinatorSnapshot(
        queueState = state,
        status = status,
        localPlayback = localPlayback,
        currentTitle = currentTitle,
        diagnostics = diagnostics,
        error = error,
        generation = generation,
        storageWarning = storageWarning,
        updatedAtMs = updatedAtMs,
    )
}

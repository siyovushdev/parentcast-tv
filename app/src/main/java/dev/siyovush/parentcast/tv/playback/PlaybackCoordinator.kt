package dev.siyovush.parentcast.tv.playback

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
) : PlaybackPlayer.Listener {
    private var state = QueueState.Empty
    private var status = PlaybackStatus.IDLE
    private var currentTitle = ""
    private var diagnostics = ""
    private var error: PlaybackFailure? = null
    private var storageWarning = false
    private var generation = 0L
    private var extractionJob: Job? = null
    private var preparedItemId: String? = null
    private var refreshAttempted = false
    private var listener: ((CoordinatorSnapshot) -> Unit)? = null
    private var started = false
    private var closed = false

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

    fun play() {
        if (closed || state.items.isEmpty()) return
        state = state.play()
        error = null
        refreshAttempted = false
        persist()
        val current = state.currentItem ?: return
        if (preparedItemId == current.queueItemId) {
            status = PlaybackStatus.BUFFERING
            player.play()
            emit()
        } else {
            prepareCurrent()
        }
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

    fun deletePending(queueItemId: String) {
        if (closed) return
        val updated = state.deletePending(queueItemId)
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
        status = PlaybackStatus.ERROR
        error = if (cause is YouTubeSourceException) {
            cause.failure
        } else {
            PlaybackFailure(PlaybackErrorKind.EXTRACTOR)
        }
        emit()
    }

    private fun prepareCurrent(preserveRefreshAttempt: Boolean = false) {
        val current = state.currentItem ?: return
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
        store.write(state)
    }

    private fun emit() {
        listener?.invoke(
            CoordinatorSnapshot(
                queueState = state,
                status = status,
                currentTitle = currentTitle,
                diagnostics = diagnostics,
                error = error,
                generation = generation,
                storageWarning = storageWarning,
            ),
        )
    }
}

package dev.siyovush.parentcast.tv.playback

import dev.siyovush.parentcast.tv.queue.QueueState
import dev.siyovush.parentcast.tv.youtube.ResolvedVideo

enum class PlaybackErrorKind {
    INVALID_LINK,
    UNAVAILABLE,
    UNSUPPORTED,
    NETWORK,
    EXTRACTOR,
    DECODER,
    PLAYBACK,
}

data class PlaybackFailure(
    val kind: PlaybackErrorKind,
    val canRefresh: Boolean = false,
    val diagnostic: String = "",
    val stopRequested: Boolean = false,
)

enum class PlaybackStatus {
    IDLE,
    WAITING_FOR_SESSION,
    LOADING,
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED,
    ENDED,
    ERROR,
}

data class CoordinatorSnapshot(
    val queueState: QueueState = QueueState.Empty,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val currentTitle: String = "",
    val diagnostics: String = "",
    val error: PlaybackFailure? = null,
    val generation: Long = 0,
    val storageWarning: Boolean = false,
    val updatedAtMs: Long = 0,
)

interface PlaybackPlayer {
    fun setListener(listener: Listener)
    fun prepare(generation: Long, video: ResolvedVideo, positionMs: Long, playWhenReady: Boolean)
    fun play()
    fun pause()
    fun stop()
    fun currentPositionMs(): Long
    fun release()

    interface Listener {
        fun onEnded(generation: Long) = Unit
        fun onError(generation: Long, failure: PlaybackFailure) = Unit
        fun onFirstFrame(generation: Long) = Unit
        fun onPlaying(generation: Long) = Unit
        fun onBuffering(generation: Long) = Unit
        fun onDiagnostics(generation: Long, diagnostics: String) = Unit
    }
}

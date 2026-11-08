package dev.siyovush.parentcast.tv.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import dev.siyovush.parentcast.tv.youtube.ResolvedVideo
import dev.siyovush.parentcast.tv.youtube.StreamSpec
import dev.siyovush.parentcast.tv.youtube.YOUTUBE_USER_AGENT
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

@androidx.annotation.OptIn(UnstableApi::class)
class Media3PlaybackPlayer(
    context: android.content.Context,
    private val session: dev.siyovush.parentcast.tv.youtube.YouTubeSession =
        dev.siyovush.parentcast.tv.youtube.DisabledYouTubeSession,
    private val allowLocalFile: (String, java.io.File) -> Boolean = { _, _ -> false },
    private val streamDataSource: (StreamSpec) -> androidx.media3.datasource.DataSource.Factory? = { null },
) : PlaybackPlayer {
    private val context = context.applicationContext
    private var playerView: PlayerView? = null
    private val player = ExoPlayer.Builder(this.context).setLoadControl(
        androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(30_000,90_000,3_000,5_000)
            .setTargetBufferBytes(48*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build()
    ).build()
    private var listener: PlaybackPlayer.Listener? = null
    private var activeGeneration = 0L
    private var activeVideo: ResolvedVideo? = null
    private var hasRenderedFirstFrame = false
    private var rebufferCount = 0

    init {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true,
        )
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            if(hasRenderedFirstFrame && player.playWhenReady)rebufferCount++
                            listener?.onBuffering(activeGeneration);dispatchDiagnostics()
                        }
                        Player.STATE_ENDED -> listener?.onEnded(activeGeneration)
                        Player.STATE_READY -> {
                            if (hasRenderedFirstFrame && !player.playWhenReady) listener?.onFirstFrame(activeGeneration)
                            dispatchDiagnostics()
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && hasRenderedFirstFrame) listener?.onPlaying(activeGeneration)
                }

                override fun onRenderedFirstFrame() {
                    hasRenderedFirstFrame = true
                    if (player.playbackState == Player.STATE_READY) listener?.onFirstFrame(activeGeneration)
                    dispatchDiagnostics()
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    dispatchDiagnostics()
                }

                override fun onPlayerError(error: PlaybackException) {
                    listener?.onError(activeGeneration, mapError(error))
                }
            },
        )
    }

    fun hasVisibleView(): Boolean = playerView?.isAttachedToWindow == true
    fun attachView(view: PlayerView) {
        if (playerView !== view) { playerView?.player = null; playerView = view }
        view.player = player
    }
    fun detachView(view: PlayerView) {
        if (playerView === view) { view.player = null; playerView = null }
    }

    override fun setListener(listener: PlaybackPlayer.Listener) {
        this.listener = listener
    }

    override fun prepare(
        generation: Long,
        video: ResolvedVideo,
        positionMs: Long,
        playWhenReady: Boolean,
    ) {
        activeGeneration = generation
        activeVideo = video
        rebufferCount = 0
        hasRenderedFirstFrame = false
        val videoSource = progressiveSource(video.videoId, video.video)
        val source = video.audio?.let { audio ->
            MergingMediaSource(videoSource, progressiveSource(video.videoId, audio))
        } ?: videoSource
        player.setMediaSource(source)
        player.seekTo(positionMs.coerceAtLeast(0))
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun stop() {
        activeGeneration = 0
        activeVideo = null
        hasRenderedFirstFrame = false
        player.stop()
        player.clearMediaItems()
    }

    override fun durationMs(): Long? = player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
    override fun isSeekable(): Boolean = player.isCurrentMediaItemSeekable && durationMs() != null &&
        player.playbackState in setOf(Player.STATE_READY, Player.STATE_BUFFERING)
    override fun seekTo(positionMs: Long) { player.seekTo(positionMs) }

    override fun currentPositionMs(): Long = player.currentPosition.coerceAtLeast(0)
    override fun bufferedPositionMs():Long = player.bufferedPosition.coerceAtLeast(0)

    override fun release() {
        activeGeneration = 0
        activeVideo = null
        listener = null
        playerView?.player = null
        playerView = null
        player.release()
    }

    private fun progressiveSource(videoId: String, spec: StreamSpec): MediaSource {
        if(spec.onlineKey!=null) {
            require(spec.url.startsWith("parentcast-online://"))
            val factory=requireNotNull(streamDataSource(spec)) { "Online source unavailable" }
            return ProgressiveMediaSource.Factory(factory).setLoadErrorHandlingPolicy(dev.siyovush.parentcast.tv.streaming.SharedLoadErrorPolicy()).createMediaSource(MediaItem.Builder().setUri(spec.url).setMimeType(spec.mimeType).build())
        }
        if(spec.sharedCacheKey!=null) {
            require(spec.url.startsWith("parentcast-cache://"))
            val factory=requireNotNull(streamDataSource(spec)) { "Shared cache unavailable" }
            return ProgressiveMediaSource.Factory(factory).setLoadErrorHandlingPolicy(dev.siyovush.parentcast.tv.streaming.SharedLoadErrorPolicy()).createMediaSource(MediaItem.Builder().setUri(spec.url).setMimeType(spec.mimeType).build())
        }
        val headers = spec.headers
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .toMutableMap()
        val isLocal = spec.url.startsWith("file:")
        require(isLocal || spec.provider != dev.siyovush.parentcast.tv.media.MediaProvider.VK) { "VK requires a prepared private file" }
        if (isLocal) {
            val file = java.io.File(java.net.URI(spec.url)).canonicalFile
            // Only catalogue-approved tracks on an available application-owned volume.
            require(allowLocalFile(videoId, file)) { "Prepared file is unavailable or outside the library" }
        }
        val cookieHeader = if (isLocal) null else dev.siyovush.parentcast.tv.youtube.CookieHeaderCodec.merge(
            emptyList(), session.cookiesFor(spec.url.toHttpUrl()),
        )
        if (cookieHeader != null) headers["Cookie"] = cookieHeader
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(YOUTUBE_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
        val mediaItem = MediaItem.Builder()
            .setUri(spec.url)
            .setMimeType(spec.mimeType)
            .build()
        return ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context, dataSource)).createMediaSource(mediaItem)
    }

    private fun dispatchDiagnostics() {
        val video = activeVideo ?: return
        val diagnostics = buildString {
            append("Выбор источника: ")
            append(video.selectionDescription)
            append("\nПовторных буферизаций: ");append(rebufferCount)
            append("\n")
            append("Фактическое видео: ")
            append(formatDescription(player.videoFormat, isVideo = true))
            append("\n")
            append("Фактическое аудио: ")
            append(formatDescription(player.audioFormat, isVideo = false))
        }
        listener?.onDiagnostics(activeGeneration, diagnostics)
    }

    private fun formatDescription(format: Format?, isVideo: Boolean): String {
        if (format == null) return "дорожка ещё не выбрана"
        val codec = format.codecs ?: format.sampleMimeType ?: "кодек неизвестен"
        return if (isVideo && format.width > 0 && format.height > 0) {
            "${format.width}x${format.height}, $codec"
        } else {
            codec
        }
    }

    private fun mapError(error: PlaybackException): PlaybackFailure =
        classifyPlaybackError(error,activeVideo?.video?.onlineKey!=null)
}

/** Only a selected direct-online source may be reopened automatically. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun classifyPlaybackError(error:PlaybackException,online:Boolean):PlaybackFailure {
    val causes=generateSequence(error as Throwable){it.cause}.take(12).toList()
    val reason=causes.filterIsInstance<dev.siyovush.parentcast.tv.streaming.RangeFailure>().firstOrNull()?.reason
    val forbidden=causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().any {it.responseCode==403}
    val decoder=error.errorCode in setOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
    val parser=causes.any {it is androidx.media3.common.ParserException}
    val network=causes.any {it is IOException}
    val retryable=reason in setOf("NETWORK_RETRY","URL_EXPIRED") || (reason==null && network && !parser)
    val recovery=when {
        !online || decoder -> OnlineRecovery.NONE
        reason!=null && !retryable -> OnlineRecovery.NONE
        parser -> OnlineRecovery.CONTAINER
        retryable -> OnlineRecovery.NETWORK
        else -> OnlineRecovery.NONE
    }
    val kind=when {
        decoder -> PlaybackErrorKind.DECODER
        parser && !online -> PlaybackErrorKind.PLAYBACK
        reason in setOf("UNSUPPORTED_MEDIA_HOST","RANGE_UNSUPPORTED","INVALID_RANGE","REPRESENTATION_CHANGED","QUALITY_UNAVAILABLE") -> PlaybackErrorKind.UNSUPPORTED
        reason=="UNAVAILABLE" -> PlaybackErrorKind.UNAVAILABLE
        network -> PlaybackErrorKind.NETWORK
        else -> PlaybackErrorKind.PLAYBACK
    }
    return PlaybackFailure(kind,canRefresh=forbidden,diagnostic=reason ?: if(parser) (if(online) "ONLINE_CONTAINER_READ" else "FILE_DAMAGED") else "",
        stopRequested=reason=="YOUTUBE_AUTH_REQUIRED",onlineRecovery=recovery)
}

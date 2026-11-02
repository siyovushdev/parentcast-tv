package dev.siyovush.parentcast.tv.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
    private val playerView: PlayerView,
    private val session: dev.siyovush.parentcast.tv.youtube.YouTubeSession =
        dev.siyovush.parentcast.tv.youtube.DisabledYouTubeSession,
) : PlaybackPlayer {
    private val player = ExoPlayer.Builder(playerView.context).build()
    private var listener: PlaybackPlayer.Listener? = null
    private var activeGeneration = 0L
    private var activeVideo: ResolvedVideo? = null
    private var hasRenderedFirstFrame = false

    init {
        playerView.player = player
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
                        Player.STATE_BUFFERING -> listener?.onBuffering(activeGeneration)
                        Player.STATE_ENDED -> listener?.onEnded(activeGeneration)
                        Player.STATE_READY -> dispatchDiagnostics()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && hasRenderedFirstFrame) listener?.onPlaying(activeGeneration)
                }

                override fun onRenderedFirstFrame() {
                    hasRenderedFirstFrame = true
                    listener?.onFirstFrame(activeGeneration)
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
        hasRenderedFirstFrame = false
        val videoSource = progressiveSource(video.video)
        val source = video.audio?.let { audio ->
            MergingMediaSource(videoSource, progressiveSource(audio))
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

    override fun currentPositionMs(): Long = player.currentPosition.coerceAtLeast(0)

    override fun release() {
        activeGeneration = 0
        activeVideo = null
        listener = null
        playerView.player = null
        player.release()
    }

    private fun progressiveSource(spec: StreamSpec): MediaSource {
        val headers = spec.headers
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .toMutableMap()
        val cookieHeader = dev.siyovush.parentcast.tv.youtube.CookieHeaderCodec.merge(
            emptyList(),
            session.cookiesFor(spec.url.toHttpUrl()),
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
        return ProgressiveMediaSource.Factory(dataSource).createMediaSource(mediaItem)
    }

    private fun dispatchDiagnostics() {
        val video = activeVideo ?: return
        val diagnostics = buildString {
            append("Выбор источника: ")
            append(video.selectionDescription)
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

    private fun mapError(error: PlaybackException): PlaybackFailure {
        val causes = generateSequence(error as Throwable) { it.cause }.toList()
        val forbidden = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .any { it.responseCode == 403 }
        val decoder = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        val network = causes.any { it is IOException }
        val kind = when {
            decoder -> PlaybackErrorKind.DECODER
            network -> PlaybackErrorKind.NETWORK
            else -> PlaybackErrorKind.PLAYBACK
        }
        return PlaybackFailure(kind, canRefresh = forbidden)
    }
}

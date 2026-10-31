package dev.siyovush.parentcast.tv.youtube

import dev.siyovush.parentcast.tv.playback.PlaybackErrorKind
import dev.siyovush.parentcast.tv.playback.PlaybackFailure
import kotlinx.coroutines.CancellationException
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.net.URI

class NewPipeYouTubeSource : YouTubeSource {
    init {
        ensureInitialized()
    }

    override suspend fun resolve(videoId: String): ResolvedVideo {
        try {
            require(Regex("[A-Za-z0-9_-]{11}").matches(videoId))
            val info = StreamInfo.getInfo(
                ServiceList.YouTube,
                "https://www.youtube.com/watch?v=$videoId",
            )
            if (info.streamType != StreamType.VIDEO_STREAM) {
                throw sourceError(PlaybackErrorKind.UNSUPPORTED)
            }
            return selectStreams(info)
        } catch (error: CancellationException) {
            throw error
        } catch (error: YouTubeSourceException) {
            throw error
        } catch (error: ContentNotSupportedException) {
            throw sourceError(PlaybackErrorKind.UNSUPPORTED, error)
        } catch (error: ContentNotAvailableException) {
            throw sourceError(PlaybackErrorKind.UNAVAILABLE, error)
        } catch (error: IOException) {
            throw sourceError(PlaybackErrorKind.NETWORK, error)
        } catch (error: ExtractionException) {
            throw sourceError(PlaybackErrorKind.EXTRACTOR, error)
        } catch (error: Exception) {
            throw sourceError(PlaybackErrorKind.EXTRACTOR, error)
        }
    }

    private fun selectStreams(info: StreamInfo): ResolvedVideo {
        val progressive = bestVideo(info.videoStreams.orEmpty())
        val videoOnly = bestVideo(info.videoOnlyStreams.orEmpty())
        val selected = listOfNotNull(progressive, videoOnly)
            .maxWithOrNull(
                compareBy<VideoStream> { heightOf(it) }
                    .thenBy { it.bitrate },
            )
            ?: throw sourceError(PlaybackErrorKind.UNSUPPORTED)

        if (!selected.isVideoOnly()) {
            return resolved(info, selected, null)
        }

        val audio = bestAudio(info.audioStreams.orEmpty())
        if (audio != null) {
            return resolved(info, selected, audio)
        }
        return resolved(
            info,
            progressive ?: throw sourceError(PlaybackErrorKind.UNSUPPORTED),
            null,
        )
    }

    private fun resolved(info: StreamInfo, video: VideoStream, audio: AudioStream?): ResolvedVideo {
        val videoSpec = StreamSpec(
            url = video.content,
            mimeType = "video/mp4",
            headers = mapOf("User-Agent" to YOUTUBE_USER_AGENT),
        )
        val audioSpec = audio?.let {
            StreamSpec(
                url = it.content,
                mimeType = "audio/mp4",
                headers = mapOf("User-Agent" to YOUTUBE_USER_AGENT),
            )
        }
        val trackLayout = if (audio == null) "совмещённые дорожки" else "раздельные дорожки"
        return ResolvedVideo(
            videoId = info.id,
            title = info.name,
            durationSeconds = info.duration,
            video = videoSpec,
            audio = audioSpec,
            selectionDescription = "${heightOf(video)}p, H.264/AAC, $trackLayout",
        )
    }

    private fun bestVideo(streams: List<VideoStream>): VideoStream? = streams
        .filter { stream ->
            stream.isUrl &&
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                stream.format == MediaFormat.MPEG_4 &&
                stream.codec?.startsWith("avc1", ignoreCase = true) == true &&
                isHttps(stream.content) &&
                heightOf(stream) in 1..1080
        }
        .maxWithOrNull(compareBy<VideoStream> { heightOf(it) }.thenBy { it.bitrate })

    private fun bestAudio(streams: List<AudioStream>): AudioStream? = streams
        .filter { stream ->
            stream.isUrl &&
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                stream.format == MediaFormat.M4A &&
                stream.codec?.startsWith("mp4a", ignoreCase = true) == true &&
                isHttps(stream.content)
        }
        .maxByOrNull { stream ->
            if (stream.averageBitrate > 0) stream.averageBitrate else stream.bitrate
        }

    private fun heightOf(stream: VideoStream): Int {
        if (stream.height > 0) return stream.height
        return Regex("(\\d{3,4})p").find(stream.resolution)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: 0
    }

    private fun isHttps(url: String): Boolean = try {
        URI(url).scheme.equals("https", ignoreCase = true)
    } catch (error: Exception) {
        false
    }

    private fun sourceError(
        kind: PlaybackErrorKind,
        cause: Throwable? = null,
    ) = YouTubeSourceException(PlaybackFailure(kind), cause)

    companion object {
        private val initializationLock = Any()

        @Volatile
        private var initialized = false

        private fun ensureInitialized() {
            if (initialized) return
            synchronized(initializationLock) {
                if (!initialized) {
                    NewPipe.init(OkHttpDownloader())
                    initialized = true
                }
            }
        }
    }
}

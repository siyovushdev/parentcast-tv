package dev.siyovush.parentcast.tv.youtube

import dev.siyovush.parentcast.tv.playback.PlaybackFailure

data class StreamSpec(
    val url: String,
    val mimeType: String,
    val headers: Map<String, String>,
)

data class ResolvedVideo(
    val videoId: String,
    val title: String,
    val durationSeconds: Long,
    val video: StreamSpec,
    val audio: StreamSpec?,
    val selectionDescription: String,
)

interface YouTubeSource {
    suspend fun resolve(videoId: String): ResolvedVideo
}

class YouTubeSourceException(
    val failure: PlaybackFailure,
    cause: Throwable? = null,
) : Exception(failure.kind.name, cause)

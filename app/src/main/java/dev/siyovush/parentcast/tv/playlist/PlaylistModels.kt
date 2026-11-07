package dev.siyovush.parentcast.tv.playlist

enum class PlaylistAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN, UNSUPPORTED }
enum class PlaylistStatus { COMPLETE, PARTIAL, LIMIT_EXCEEDED, ERROR }

data class PlaylistEntry(
    val index: Int,
    val videoId: String?,
    val title: String,
    val availability: PlaylistAvailability,
    val selectable: Boolean,
    val durationSeconds: Long?,
    val channel: String?,
)

data class PlaylistResult(
    val title: String,
    val entries: List<PlaylistEntry>,
    val status: PlaylistStatus,
    val reason: String?,
    val reportedCount: Int?,
    val provider: dev.siyovush.parentcast.tv.media.MediaProvider = dev.siyovush.parentcast.tv.media.MediaProvider.YOUTUBE,
)

fun interface PlaylistSource {
    suspend fun fetch(playlistId: String): PlaylistResult
}

enum class PreviewState { LOADING, READY, CANCELLED }

data class PlaylistPreview(
    val id: String,
    val state: PreviewState,
    val result: PlaylistResult? = null,
)

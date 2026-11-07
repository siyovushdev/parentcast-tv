package dev.siyovush.parentcast.tv.youtube

import dev.siyovush.parentcast.tv.playback.PlaybackFailure

data class StreamSpec(
    val url: String,
    val mimeType: String,
    val headers: Map<String, String>,
    val sizeBytes: Long? = null,
    val resumeIdentity: String = "",
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

internal fun Throwable?.safeDiagnostic(): String {
    if (this == null) return ""
    val urlPattern = Regex("https?://\\S+")
    val sensitivePattern = Regex(
        "(?i)(cookie|authorization|access_token|refresh_token|visitorData|poToken|pot|token|signature|sap|sig|expire|sapisid|apisid|hsid|ssid|sid|__secure-1psid|__secure-3psid|__secure-1psidts|__secure-3psidts)=[^\\s&]+",
    )
    val bearerPattern = Regex("(?i)bearer\\s+\\S+")
    return generateSequence(this) { it.cause }
        .take(6)
        .joinToString(" <- ") { error ->
            val message = error.message.orEmpty()
                .replace(urlPattern, "<URL>")
                .replace(sensitivePattern, "$1=<redacted>")
                .replace(bearerPattern, "Bearer <redacted>")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(300)
            if (message.isEmpty()) error.javaClass.name else "${error.javaClass.name}: $message"
        }
        .take(800)
}

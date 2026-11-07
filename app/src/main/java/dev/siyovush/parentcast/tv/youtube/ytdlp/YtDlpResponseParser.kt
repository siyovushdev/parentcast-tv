package dev.siyovush.parentcast.tv.youtube.ytdlp

import com.fasterxml.jackson.databind.JsonNode
import dev.siyovush.parentcast.tv.playback.PlaybackErrorKind
import dev.siyovush.parentcast.tv.playback.PlaybackFailure
import dev.siyovush.parentcast.tv.youtube.ResolvedVideo
import dev.siyovush.parentcast.tv.youtube.StreamSpec
import dev.siyovush.parentcast.tv.youtube.YouTubeSourceException
import dev.siyovush.parentcast.tv.youtube.YOUTUBE_USER_AGENT
import java.net.URI

class YtDlpResponseParser {
    fun parse(root: JsonNode, fallbackVideoId: String): ResolvedVideo {
        val requested = root.get("requested_formats")
            ?.takeIf { it.isArray && it.size() > 0 }
            ?.toList()
            ?: listOf(root)
        val video = requested.singleOrNull { node ->
            val codec = node.text("vcodec")
            codec != null && codec != "none"
        } ?: throw unsupported()
        val videoCodec = video.text("vcodec").orEmpty()
        requireStream(videoCodec.startsWith("avc1", ignoreCase = true), "Unsupported video codec")
        requireStream(video.text("ext") == "mp4", "Unsupported video container")
        requireStream(video.get("height")?.asInt(0) in 1..1080, "Unsupported video height")

        val embeddedAudio = video.text("acodec")?.takeUnless { it == "none" }
        val audio = if (embeddedAudio == null) {
            requested.singleOrNull { node ->
                node.text("vcodec") in listOf(null, "none") &&
                    node.text("acodec")?.startsWith("mp4a", ignoreCase = true) == true
            } ?: throw unsupported()
        } else {
            null
        }
        if (embeddedAudio != null) {
            requireStream(embeddedAudio.startsWith("mp4a", ignoreCase = true), "Unsupported audio codec")
        }

        val videoSpec = streamSpec(video, root, isVideo = true)
        val audioSpec = audio?.let { streamSpec(it, root, isVideo = false) }
        val layout = if (audioSpec == null) "совмещённые дорожки" else "раздельные дорожки"
        return ResolvedVideo(
            videoId = root.text("id") ?: fallbackVideoId,
            title = root.text("fulltitle") ?: root.text("title").orEmpty(),
            durationSeconds = root.get("duration")?.asLong(0) ?: 0,
            video = videoSpec,
            audio = audioSpec,
            selectionDescription = "${video.get("height").asInt()}p, H.264/AAC, $layout, yt-dlp/QuickJS",
        )
    }

    private fun streamSpec(node: JsonNode, root: JsonNode, isVideo: Boolean): StreamSpec {
        val protocol = node.text("protocol")
        requireStream(protocol == "http" || protocol == "https", "Only progressive HTTP is supported")
        val url = node.text("url").orEmpty()
        requireStream(
            runCatching { URI(url).scheme == "https" }.getOrDefault(false),
            "Only HTTPS media URLs are supported",
        )
        return StreamSpec(
            url = url,
            mimeType = if (isVideo) "video/mp4" else "audio/mp4",
            headers = allowedHeaders(node.get("http_headers") ?: root.get("http_headers")),
            sizeBytes = node.get("filesize")?.asLong()?.takeIf { it > 0 }
                ?: runCatching { URI(url).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("clen=") }
                    ?.substringAfter('=')?.toLongOrNull()?.takeIf { it > 0 } }.getOrNull(),
        )
    }

    private fun allowedHeaders(node: JsonNode?): Map<String, String> {
        val headers = mutableMapOf("User-Agent" to YOUTUBE_USER_AGENT)
        node?.fields()?.forEach { (name, value) ->
            if (name.lowercase() in ALLOWED_HEADERS && value.isTextual) {
                headers[name] = value.asText()
            }
        }
        return headers
    }

    private fun JsonNode.text(name: String): String? = get(name)
        ?.takeUnless { it.isNull }
        ?.asText()
        ?.takeUnless { it.isBlank() }

    private fun unsupported() = YouTubeSourceException(
        PlaybackFailure(
            PlaybackErrorKind.UNSUPPORTED,
            diagnostic = "No progressive H.264/AAC stream up to 1080p",
        ),
    )

    private fun requireStream(condition: Boolean, message: String) {
        if (!condition) {
            throw YouTubeSourceException(
                PlaybackFailure(PlaybackErrorKind.UNSUPPORTED, diagnostic = message),
            )
        }
    }

    companion object {
        private val ALLOWED_HEADERS = setOf(
            "user-agent",
            "accept",
            "accept-language",
            "accept-encoding",
            "origin",
            "referer",
            "range",
            "sec-fetch-mode",
            "x-youtube-client-name",
            "x-youtube-client-version",
        )
    }
}

package dev.siyovush.parentcast.tv.youtube

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

object YouTubeVideoIdParser {
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    private val watchHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
    private val shortHosts = setOf("youtu.be", "www.youtu.be")

    fun parse(rawLink: String): String {
        val uri = try {
            URI(rawLink.trim())
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid YouTube video link", error)
        }
        require(uri.scheme?.lowercase(Locale.US) == "https")
        require(uri.userInfo == null)
        require(uri.port == -1 || uri.port == 443)
        val host = uri.host?.lowercase(Locale.US)
        val videoId = when {
            host in watchHosts && uri.path == "/watch" -> watchVideoId(uri.rawQuery)
            host in watchHosts -> pathVideoId(uri.path, "shorts")
            host in shortHosts -> pathVideoId(uri.path, null)
            else -> null
        }
        require(!videoId.isNullOrEmpty() && videoIdPattern.matches(videoId))
        return videoId
    }

    private fun watchVideoId(rawQuery: String?): String? {
        if (rawQuery.isNullOrEmpty()) return null
        val values = rawQuery.split('&')
            .mapNotNull { parameter ->
                val parts = parameter.split('=', limit = 2)
                val name = URLDecoder.decode(parts[0], "UTF-8")
                if (name == "v") {
                    URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                } else {
                    null
                }
            }
        return values.singleOrNull()
    }

    private fun pathVideoId(path: String?, section: String?): String? {
        val segments = path.orEmpty().split('/').filter(String::isNotEmpty)
        return when {
            section == null && segments.size == 1 -> segments[0]
            section != null && segments.size == 2 && segments[0] == section -> segments[1]
            else -> null
        }
    }
}

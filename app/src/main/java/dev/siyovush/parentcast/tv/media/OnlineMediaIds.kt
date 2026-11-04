package dev.siyovush.parentcast.tv.media

import dev.siyovush.parentcast.tv.playlist.YouTubePlaylistIdParser
import dev.siyovush.parentcast.tv.youtube.YouTubeVideoIdParser
import java.net.URI
import java.net.URLDecoder

/** Provider-qualified VK IDs cannot collide with an eleven-character YouTube ID. */
object OnlineMediaIds {
    const val VK_PATTERN = "vk:-?[1-9][0-9]{0,18}_[1-9][0-9]{0,18}"
    const val ONLINE_PATTERN = "(?:[A-Za-z0-9_-]{11}|$VK_PATTERN)"
    const val MEDIA_PATTERN = "(?:$ONLINE_PATTERN|local_[0-9a-f]{32})"
    private val youtube = Regex("[A-Za-z0-9_-]{11}")
    private val pair = Regex("(-?[0-9]{1,19})_([0-9]{1,19})")
    private val hosts = setOf("vkvideo.ru", "www.vkvideo.ru", "m.vkvideo.ru", "new.vkvideo.ru", "vk.com", "www.vk.com", "m.vk.com", "new.vk.com")

    fun vk(raw: String): String = "vk:${canonicalPair(raw)}"
    fun isVk(id: String): Boolean = id.startsWith("vk:") && runCatching { vk(id.removePrefix("vk:")) == id }.getOrDefault(false)
    fun isOnline(id: String): Boolean = youtube.matches(id) || isVk(id)
    fun videoUrl(id: String): String {
        require(isOnline(id))
        return if (isVk(id)) "https://vkvideo.ru/video${id.removePrefix("vk:")}" else "https://www.youtube.com/watch?v=$id"
    }
    fun parseLink(input: String): String {
        require(input.length in 1..2048)
        val uri = URI(input.trim())
        if (uri.host?.lowercase() !in hosts) return YouTubeVideoIdParser.parse(input)
        validateVk(uri)
        val path = uri.rawPath.removeSuffix("/")
        require(path.startsWith("/video") && !path.startsWith("/video/"))
        return vk(path.removePrefix("/video"))
    }
    fun parsePlaylist(input: String): String {
        require(input.length in 1..2048)
        val uri = URI(input.trim())
        if (uri.host?.lowercase() !in hosts) return YouTubePlaylistIdParser.parse(input)
        validateVk(uri)
        val path = uri.rawPath.removeSuffix("/")
        val raw = when {
            path.startsWith("/playlist/") -> path.removePrefix("/playlist/").removeSuffix("/season_0")
            path.startsWith("/video/playlist/") -> path.removePrefix("/video/playlist/").removeSuffix("/season_0")
            path.startsWith("/video") -> {
                parseLink(input) // The video-with-context alias must itself be a valid VK video URL.
                parameters(uri).filter { it.first == "pl" }.singleOrNull()?.second ?: throw IllegalArgumentException("Missing playlist ID")
            }
            else -> throw IllegalArgumentException("Unsupported VK playlist link")
        }
        return "vkpl:${canonicalPair(raw)}"
    }
    fun isVkPlaylist(id: String): Boolean = id.startsWith("vkpl:") && runCatching { canonicalPair(id.removePrefix("vkpl:")) == id.removePrefix("vkpl:") }.getOrDefault(false)
    fun playlistUrl(id: String): String {
        require(isVkPlaylist(id))
        return "https://vkvideo.ru/playlist/${id.removePrefix("vkpl:")}/"
    }
    private fun canonicalPair(raw: String): String {
        val m = pair.matchEntire(raw) ?: throw IllegalArgumentException("Invalid VK ID")
        val owner = m.groupValues[1].toLongOrNull() ?: throw IllegalArgumentException("Invalid VK owner")
        val item = m.groupValues[2].toLongOrNull() ?: throw IllegalArgumentException("Invalid VK item")
        require(owner != 0L && item > 0)
        return "${owner}_$item"
    }
    private fun parameters(uri: URI) = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
        val p = it.split('=', limit = 2)
        URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
    }
    private fun validateVk(uri: URI) {
        require(uri.scheme?.lowercase() == "https" && uri.rawUserInfo == null && uri.port in setOf(-1,443) && uri.rawFragment == null)
        require(parameters(uri).all { it.first in setOf("pl", "from", "t") || it.first.startsWith("utm_") })
    }
}

enum class MediaProvider { YOUTUBE, VK }

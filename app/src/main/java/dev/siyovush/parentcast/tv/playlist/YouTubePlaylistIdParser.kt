package dev.siyovush.parentcast.tv.playlist

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Returns an ID only; the extractor never receives a caller-supplied URL. */
object YouTubePlaylistIdParser {
    private val idPattern = Regex("(?:PL[A-Za-z0-9_-]{10,80}|OLAK5uy_[A-Za-z0-9_-]{10,80})")
    private val hosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be")

    fun parse(input: String): String {
        require(input.length in 1..2048)
        val uri = URI(input.trim())
        require(uri.scheme?.lowercase() in setOf("http", "https"))
        require(uri.host?.lowercase() in hosts && uri.rawUserInfo == null && uri.port == -1)
        require(uri.rawFragment == null)
        require(uri.rawPath == "/playlist" || uri.rawPath == "/watch" ||
            (uri.host?.lowercase() in setOf("youtu.be", "www.youtu.be") && Regex("/[A-Za-z0-9_-]{11}").matches(uri.rawPath.orEmpty())))
        val parameters = uri.rawQuery.orEmpty().split('&').map { parameter ->
            val parts = parameter.split('=', limit = 2)
            decode(parts[0]) to decode(parts.getOrElse(1) { "" })
        }
        val lists = parameters.filter { it.first == "list" }
        require(lists.size == 1)
        return lists.single().second.also { require(isSupportedId(it)) }
    }

    fun isSupportedId(id: String): Boolean = idPattern.matches(id)

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

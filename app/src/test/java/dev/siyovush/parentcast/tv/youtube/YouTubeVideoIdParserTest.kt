package dev.siyovush.parentcast.tv.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeVideoIdParserTest {
    private val videoId = "dQw4w9WgXcQ"

    @Test
    fun parsesWatchLink() {
        assertEquals(videoId, YouTubeVideoIdParser.parse("https://www.youtube.com/watch?v=$videoId"))
    }

    @Test
    fun parsesShortLinkAndIgnoresTime() {
        assertEquals(videoId, YouTubeVideoIdParser.parse("https://youtu.be/$videoId?t=42&utm_source=test"))
    }

    @Test
    fun parsesShortsLink() {
        assertEquals(
            videoId,
            YouTubeVideoIdParser.parse("https://m.youtube.com/shorts/$videoId?feature=share"),
        )
    }

    @Test
    fun playlistParameterDoesNotImportPlaylist() {
        assertEquals(
            videoId,
            YouTubeVideoIdParser.parse("https://youtube.com/watch?v=$videoId&list=PL123&index=2"),
        )
    }

    @Test
    fun acceptsExplicitHttpsPort() {
        assertEquals(videoId, YouTubeVideoIdParser.parse("https://youtu.be:443/$videoId"))
    }

    @Test
    fun rejectsDangerousHostnames() {
        val links = listOf(
            "https://youtube.com.evil.example/watch?v=$videoId",
            "https://youtu.be.evil.example/$videoId",
            "https://evil.example/watch?v=$videoId",
            "https://youtube.com@evil.example/watch?v=$videoId",
        )
        links.forEach(::assertInvalid)
    }

    @Test
    fun rejectsNonHttpsAndNonDefaultPort() {
        assertInvalid("http://youtube.com/watch?v=$videoId")
        assertInvalid("https://youtube.com:444/watch?v=$videoId")
    }

    @Test
    fun rejectsPlaylistOnlyAndUnsupportedPaths() {
        assertInvalid("https://youtube.com/playlist?list=PL123")
        assertInvalid("https://youtube.com/embed/$videoId")
        assertInvalid("https://youtube.com/watch?list=PL123")
    }

    @Test
    fun rejectsMissingMalformedOrAmbiguousIds() {
        assertInvalid("https://youtu.be/")
        assertInvalid("https://youtu.be/too-short")
        assertInvalid("https://youtube.com/watch?v=$videoId&v=video000001")
        assertInvalid("https://youtube.com/watch?v=")
    }

    private fun assertInvalid(link: String) {
        assertThrows(IllegalArgumentException::class.java) {
            YouTubeVideoIdParser.parse(link)
        }
    }
}

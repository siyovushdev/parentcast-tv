package dev.siyovush.parentcast.tv.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubePlaylistIdParserTest {
    private val id = "PL1234567890abcdefghij"

    @Test fun acceptsCanonicalWatchAndShortLinksAndDropsTracking() {
        listOf(
            "https://www.youtube.com/playlist?list=$id",
            "https://youtube.com/watch?v=jNQXAC9IVRw&list=$id&index=2",
            "https://youtu.be/jNQXAC9IVRw?list=$id&si=tracking",
            "http://m.youtube.com/playlist?list=$id",
            "https://music.youtube.com/playlist?list=OLAK5uy_1234567890abcdef",
        ).forEach { input ->
            assertEquals(if ("OLAK5uy" in input) "OLAK5uy_1234567890abcdef" else id, YouTubePlaylistIdParser.parse(input))
        }
    }

    @Test fun rejectsArbitraryUrlsLocalFilesMixAndAmbiguity() {
        listOf(
            id, "file:///etc/passwd?list=$id", "ftp://youtube.com/playlist?list=$id",
            "https://127.0.0.1/playlist?list=$id", "https://youtube.com.evil.test/playlist?list=$id",
            "https://evil.test@youtube.com/playlist?list=$id", "https://youtube.com:443/playlist?list=$id",
            "https://youtube.com/playlist?list=$id&list=$id", "https://youtube.com/playlist?list=$id#ignore",
            "https://youtube.com/redirect?list=$id", "https://youtube.com/playlist?list=RD1234567890",
            "https://youtube.com/playlist?list=UU1234567890abcdefghij", "https://youtube.com/playlist?list=WL",
            "https://youtube.com/playlist?list=LL", "https://youtube.com/playlist?list=PLtoo-short",
            "https://youtube.com/playlist?list=${id}%26url%3Dhttp%3A%2F%2F127.0.0.1",
            "https://youtu.be/arbitrary/path?list=$id",
        ).forEach { input -> assertThrows(input, IllegalArgumentException::class.java) { YouTubePlaylistIdParser.parse(input) } }
    }
}

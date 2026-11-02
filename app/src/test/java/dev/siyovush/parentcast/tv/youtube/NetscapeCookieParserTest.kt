package dev.siyovush.parentcast.tv.youtube

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetscapeCookieParserTest {
    @Test
    fun parsesYouTubeAndHttpOnlyCookiesAndIgnoresOtherDomains() {
        val serialized = """
            # Netscape HTTP Cookie File
            .youtube.com	TRUE	/	TRUE	2147483647	SAPISID	dummy-session
            #HttpOnly_.youtube.com	TRUE	/	TRUE	0	__Secure-1PSID	dummy-secure
            .example.com	TRUE	/	TRUE	2147483647	SID	unrelated-dummy
        """.trimIndent()

        val cookies = NetscapeCookieParser.parse(serialized)

        assertEquals(2, cookies.size)
        assertEquals(setOf("SAPISID", "__Secure-1PSID"), cookies.map { it.name }.toSet())
        assertTrue(cookies.all { it.domain == "youtube.com" })
        assertFalse(cookies.any { it.value == "unrelated-dummy" })
    }

    @Test
    fun rejectsFileWithoutAccountSession() {
        val serialized = ".youtube.com\tTRUE\t/\tTRUE\t2147483647\tSOCS\tCAE="
        assertThrows(IllegalArgumentException::class.java) {
            NetscapeCookieParser.parse(serialized)
        }
    }

    @Test
    fun rejectsExpiredAccountSession() {
        val serialized = ".youtube.com\tTRUE\t/\tTRUE\t1\tSAPISID\tdummy-expired"
        assertThrows(IllegalArgumentException::class.java) {
            NetscapeCookieParser.parse(serialized)
        }
    }

    @Test
    fun rejectsMalformedInput() {
        assertThrows(IllegalArgumentException::class.java) {
            NetscapeCookieParser.parse("not-a-cookie-file")
        }
    }

    @Test
    fun hostOnlyCookieMatchesOnlyItsHost() {
        val serialized = "www.youtube.com\tFALSE\t/\tTRUE\t2147483647\tSAPISID\tdummy"
        val cookie = NetscapeCookieParser.parse(serialized).single()

        assertTrue(cookie.matches("https://www.youtube.com/youtubei/v1/player".toHttpUrl()))
        assertFalse(cookie.matches("https://m.youtube.com/youtubei/v1/player".toHttpUrl()))
    }

    @Test
    fun youtubeSessionCanBeForwardedOnlyToTrustedInnerTubeHost() {
        val serialized = ".youtube.com\tTRUE\t/\tTRUE\t2147483647\tSAPISID\tdummy"
        val cookie = NetscapeCookieParser.parse(serialized).single()

        assertTrue(
            YouTubeCookiePolicy.matches(
                "https://youtubei.googleapis.com/youtubei/v1/player".toHttpUrl(),
                cookie,
            ),
        )
        assertFalse(
            YouTubeCookiePolicy.matches("https://evil.example/youtubei/v1/player".toHttpUrl(), cookie),
        )
    }
}

package dev.siyovush.parentcast.tv.youtube

import okhttp3.Cookie
import okhttp3.HttpUrl

object YouTubeCookiePolicy {
    fun matches(url: HttpUrl, cookie: Cookie): Boolean =
        cookie.matches(url) || matchesInnerTube(url, cookie)

    private fun matchesInnerTube(url: HttpUrl, cookie: Cookie): Boolean =
        url.host == "youtubei.googleapis.com" &&
            url.isHttps &&
            url.encodedPath.startsWith(cookie.path)
}

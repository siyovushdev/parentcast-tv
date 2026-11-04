package dev.siyovush.parentcast.tv.web

import okhttp3.HttpUrl

/** Explicit image CDNs only, plus the existing public-only DNS policy. No caller URL endpoint. */
internal object ThumbnailPolicy {
    fun allows(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && (url.host == "i.ytimg.com" ||
        listOf("userapi.com", "vkuser.net", "vkuserphoto.ru", "mycdn.me", "okcdn.ru").any { url.host.endsWith(".$it") })
}

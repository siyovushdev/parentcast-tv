package dev.siyovush.parentcast.tv.youtube

import okhttp3.Cookie
import okhttp3.HttpUrl

interface YouTubeSession {
    val isSupported: Boolean
        get() = false
    val isEnabled: Boolean
        get() = false
    val hasStoredSession: Boolean
        get() = false

    fun importFromPrivateFile(): Int = throw UnsupportedOperationException()
    fun disableAndDelete() = Unit
    fun cookiesFor(url: HttpUrl): List<Cookie> = emptyList()
}

object DisabledYouTubeSession : YouTubeSession

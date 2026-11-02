package dev.siyovush.parentcast.tv.youtube

import android.content.Context
import okhttp3.Cookie
import okhttp3.HttpUrl
import java.io.File

class DebugYouTubeSession(context: Context) : YouTubeSession {
    private val file = File(context.filesDir, "youtube-cookies.txt")
    private val lock = Any()

    @Volatile
    private var sessionCookies: List<Cookie> = emptyList()

    override val isSupported: Boolean
        get() = true
    override val isEnabled: Boolean
        get() = sessionCookies.isNotEmpty()
    override val hasStoredSession: Boolean
        get() = file.isFile

    override fun importFromPrivateFile(): Int = synchronized(lock) {
        check(file.isFile) { "Cookie file not found" }
        val parsed = NetscapeCookieParser.parse(file.readText(Charsets.UTF_8))
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        sessionCookies = parsed
        parsed.size
    }

    override fun disableAndDelete() {
        synchronized(lock) {
            sessionCookies = emptyList()
            check(!file.exists() || file.delete()) { "Could not delete cookie file" }
        }
    }

    override fun cookiesFor(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return sessionCookies.filter { cookie ->
            cookie.expiresAt > now && YouTubeCookiePolicy.matches(url, cookie)
        }
    }
}

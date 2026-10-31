package dev.siyovush.parentcast.tv.youtube

import okhttp3.Cookie
import java.util.Locale

object NetscapeCookieParser {
    fun parse(serialized: String): List<Cookie> {
        val now = System.currentTimeMillis()
        val cookies = serialized.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                when {
                    line.startsWith("#HttpOnly_") -> line.removePrefix("#HttpOnly_")
                    line.startsWith("#") -> null
                    else -> line
                }
            }
            .map { line -> parseCookie(line) }
            .filter { cookie -> isYouTubeDomain(cookie.domain) && cookie.expiresAt > now }
            .toList()
        require(cookies.isNotEmpty()) { "No valid YouTube cookies" }
        require(cookies.any { it.name in AUTH_COOKIE_NAMES }) { "No YouTube account session" }
        return cookies
    }

    private fun parseCookie(line: String): Cookie = try {
        val fields = line.split('\t')
        require(fields.size == 7)
        val domain = fields[0].lowercase(Locale.US)
        val includeSubdomains = fields[1].equals("TRUE", ignoreCase = true)
        val path = fields[2].ifEmpty { "/" }
        val secure = fields[3].equals("TRUE", ignoreCase = true)
        val expiresSeconds = fields[4].toLong()
        val name = fields[5]
        val value = fields[6]
        require(DOMAIN_PATTERN.matches(domain))
        require(path.startsWith('/'))
        require(COOKIE_NAME_PATTERN.matches(name))
        require(value.none { it == ';' || it == '\r' || it == '\n' })
        require(expiresSeconds >= 0)
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .path(path)
        if (includeSubdomains || domain.startsWith('.')) {
            builder.domain(domain.removePrefix("."))
        } else {
            builder.hostOnlyDomain(domain)
        }
        if (secure) builder.secure()
        if (expiresSeconds > 0) {
            builder.expiresAt(Math.multiplyExact(expiresSeconds, 1_000L))
        }
        builder.build()
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid Netscape cookies file")
    }

    private fun isYouTubeDomain(domain: String): Boolean {
        val normalized = domain.removePrefix(".")
        return normalized == "youtube.com" || normalized.endsWith(".youtube.com")
    }

    private val DOMAIN_PATTERN = Regex("\\.?[a-z0-9.-]+")
    private val COOKIE_NAME_PATTERN = Regex("[A-Za-z0-9_-]+")
    private val AUTH_COOKIE_NAMES = setOf(
        "SID",
        "SAPISID",
        "__Secure-1PSID",
        "__Secure-3PSID",
        "__Secure-1PSIDTS",
        "__Secure-3PSIDTS",
    )
}

package dev.siyovush.parentcast.tv.youtube

import okhttp3.Cookie

object CookieHeaderCodec {
    fun merge(existingHeaders: List<String>, sessionCookies: List<Cookie>): String? {
        val existing = existingHeaders
            .flatMap { it.split(';') }
            .map { it.trim() }
            .filter { it.contains('=') }
        val sessionNames = sessionCookies.mapTo(mutableSetOf()) { it.name }
        val preserved = existing.filter { it.substringBefore('=').trim() !in sessionNames }
        val session = sessionCookies.map { "${it.name}=${it.value}" }
        return (preserved + session).joinToString("; ").ifBlank { null }
    }
}

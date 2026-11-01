package dev.siyovush.parentcast.tv.web

class RequestSecurity(
    private val allowedHosts: Set<String>,
    private val port: Int,
) {
    fun isHostAllowed(hostHeader: String?): Boolean {
        if (hostHeader.isNullOrBlank()) return false
        val value = hostHeader.trim()
        val host = value.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val portPart = value.substringAfterLast(':', "")
        val portMatches = portPart.isEmpty() || portPart.toIntOrNull() == port
        return portMatches && allowedHosts.any { it.equals(host, ignoreCase = true) }
    }

    fun isOriginAllowed(origin: String?): Boolean {
        if (origin == null) return true
        return allowedHosts.any { host -> origin.equals("http://$host:$port", ignoreCase = true) }
    }

    fun isRefererAllowed(referer: String?): Boolean {
        if (referer == null) return true
        return allowedHosts.any { host -> referer.startsWith("http://$host:$port/", ignoreCase = true) }
    }

    fun isStateChangeAllowed(headers: Map<String, String>): Boolean {
        if (headers["x-parentcast-client"] != "m1") return false
        return isOriginAllowed(headers["origin"]) && isRefererAllowed(headers["referer"])
    }
}

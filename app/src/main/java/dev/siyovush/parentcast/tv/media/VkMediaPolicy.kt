package dev.siyovush.parentcast.tv.media

import okhttp3.Dns
import okhttp3.HttpUrl
import java.net.InetAddress
import java.net.UnknownHostException

/** A provider result is data, not permission to connect to an arbitrary host. */
object VkMediaPolicy {
    private val domains = setOf("okcdn.ru", "vkuser.net", "vkuserlive.net", "userapi.com", "mycdn.me", "vkvideo.ru")
    fun allows(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && domains.any { url.host.endsWith(".$it") }
    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 16 && (b[0] and 0xfe) == 0xfc) return false
        if (b.size == 4 && (b[0] == 0 || b[0] == 100 && b[1] in 64..127 || b[0] >= 224)) return false
        return true
    }
    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
            if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw UnknownHostException("VK media must resolve to public addresses")
        }
    }
}

package dev.siyovush.parentcast.tv.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.net.Inet4Address
import java.net.NetworkInterface

/** Advertises only this box; it does not enumerate other LAN devices/services. */
internal class HomeNetwork(context: Context, private val port: Int) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val multicast = app.getSystemService(WifiManager::class.java)?.createMulticastLock("ParentCast-home")?.apply { setReferenceCounted(false) }
    @Volatile private var dns: JmDNS? = null
    @Volatile private var closed = false
    private var addressKey = ""
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refresh() }
        override fun onLost(network: Network) { refresh() }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { refresh() }
    }
    init {
        multicast?.acquire()
        connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), callback)
        refresh()
    }
    fun lanHosts(): List<String> = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
        .filter { it.isUp && (it.name.startsWith("eth") || it.name.startsWith("wlan")) }
        .sortedBy { if (it.name.startsWith("eth")) 0 else 1 }
        .flatMap { java.util.Collections.list(it.inetAddresses) }
        .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }.distinct()
    fun aliases(): Set<String> = dns?.hostName?.trimEnd('.')?.lowercase()?.let { setOf(it) }.orEmpty()
    fun urls(): List<String> = (aliases().toList() + lanHosts()).map { "http://$it:$port/" }
    private fun refresh() {
        if (closed) return
        scope.launch { mutex.withLock {
            if (closed) return@withLock
            val host = lanHosts().firstOrNull().orEmpty()
            if (host == addressKey && dns != null) return@withLock
            dns?.close(); dns = null; addressKey = host
            if (host.isNotEmpty()) {
                try {
                    val candidate = JmDNS.create(java.net.InetAddress.getByName(host), "parentcast-tv")
                    try {
                        candidate.registerService(ServiceInfo.create("_http._tcp.local.", "ParentCast TV", port, "path=/"))
                        if (closed) candidate.close() else dns = candidate
                    } catch (error: Exception) { candidate.close(); throw error }
                } catch (_: Exception) { android.util.Log.w("ParentCastHome", "Local name unavailable; IP/QR remain available") }
            }
        } }
    }
    fun close() {
        closed = true
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        if (multicast?.isHeld == true) multicast.release()
        scope.launch { mutex.withLock { dns?.close(); dns = null } }.invokeOnCompletion { scope.cancel() }
    }
}

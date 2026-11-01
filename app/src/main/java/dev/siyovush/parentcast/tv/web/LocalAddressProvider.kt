package dev.siyovush.parentcast.tv.web

class LocalAddressProvider {

    fun addresses(): Set<String> = buildSet {
        add("127.0.0.1")
        add("::1")
        add("localhost")
        val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
        interfaces.forEach { networkInterface ->
            java.util.Collections.list(networkInterface.inetAddresses).forEach { address ->
                if (!address.isLoopbackAddress) {
                    add(address.hostAddress.substringBefore('%'))
                }
            }
        }
    }

    fun panelHosts(): Set<String> = addresses().filterNot { it == "localhost" }.toSet()
}

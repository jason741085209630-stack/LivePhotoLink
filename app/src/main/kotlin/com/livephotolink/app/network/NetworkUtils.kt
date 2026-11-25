package com.livephotolink.app.network

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Returns candidate local IPv4 addresses sorted by LAN priority:
     * Wi-Fi (wlan*), Mobile Hotspot / SoftAP (swlan*, ap*, rndis*), Ethernet (eth*), then other non-loopback IPv4.
     */
    fun getLocalIpv4Addresses(): List<String> {
        val candidates = mutableListOf<Pair<Int, String>>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val name = iface.name.lowercase()
                val priority = when {
                    name.startsWith("wlan") -> 1
                    name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap") -> 2
                    name.startsWith("eth") || name.startsWith("en") -> 3
                    name.startsWith("rndis") || name.startsWith("usb") -> 4
                    name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") -> 10
                    else -> 5
                }
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (isPrivateLanIp(ip) || priority <= 4) {
                            candidates.add(priority to ip)
                        }
                    }
                }
            }
        }
        return candidates
            .sortedBy { it.first }
            .map { it.second }
            .distinct()
    }

    private fun isPrivateLanIp(ip: String): Boolean {
        return ip.startsWith("192.168.") ||
            ip.startsWith("10.") ||
            ip.startsWith("172.16.") || ip.startsWith("172.17.") || ip.startsWith("172.18.") ||
            ip.startsWith("172.19.") || ip.startsWith("172.2") || ip.startsWith("172.30.") ||
            ip.startsWith("172.31.")
    }
}

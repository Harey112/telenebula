package com.telenebula.app.platform

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/** One address a browser on the same link can reach the phone at. */
data class LanAddress(val label: String, val host: String, val interfaceName: String)

/** The phone's non-overlay IPv4 addresses, named by what the interface is for. */
object LanAddresses {
    fun list(): List<LanAddress> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: SocketException) {
            return emptyList()
        }
        val out = ArrayList<LanAddress>()
        for (nic in interfaces) {
            val name = nic.name ?: continue
            if (isExcluded(name)) continue
            val isUp = try {
                nic.isUp && !nic.isLoopback
            } catch (e: SocketException) {
                false
            }
            if (!isUp) continue
            for (address in nic.inetAddresses.toList()) {
                if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) continue
                out.add(LanAddress(labelOf(name), address.hostAddress ?: continue, name))
            }
        }
        return out.sortedBy { rank(it.interfaceName) }
    }

    /** The nebula tun, the cellular link and the VPN-ish interfaces are never a browser's way in. */
    fun isExcluded(name: String): Boolean =
        name.startsWith("tun") || name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") || name.startsWith("clat") || name.startsWith("dummy") || name.startsWith("ppp")

    fun labelOf(name: String): String = when {
        name.startsWith("wlan") -> "Wi‑Fi"
        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") || name.startsWith("wifi_ap") -> "Wi‑Fi hotspot"
        name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> "USB tethering"
        name.startsWith("bt-pan") || name.startsWith("bnep") -> "Bluetooth tethering"
        name.startsWith("eth") -> "Ethernet"
        else -> name
    }

    private fun rank(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") || name.startsWith("wifi_ap") -> 1
        name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> 2
        name.startsWith("bt-pan") || name.startsWith("bnep") -> 3
        name.startsWith("eth") -> 4
        else -> 5
    }
}

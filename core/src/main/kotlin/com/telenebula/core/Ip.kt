package com.telenebula.core

import java.net.InetAddress

/**
 * One spelling per overlay address. Every address that reaches the store or the peer registry goes
 * through here first, so a peer that dials in as `::ffff:10.0.0.2`, `[fd00::2]` or `FD00::2%wlan0`
 * is still the same contact.
 */
object Ip {
    fun normalize(ip: String?): String {
        if (ip.isNullOrEmpty()) return ""
        var out = ip.trim().lowercase()
        if (out.startsWith("::ffff:")) out = out.substring("::ffff:".length)
        out = out.removePrefix("[").removeSuffix("]")
        val zone = out.indexOf('%')
        if (zone >= 0) out = out.substring(0, zone)
        return out
    }

    fun unmapped(address: InetAddress): InetAddress {
        val bytes = address.address
        val isMapped = bytes.size == 16 && (0 until 10).all { bytes[it] == 0.toByte() } && bytes[10] == MAPPED && bytes[11] == MAPPED
        return if (isMapped) InetAddress.getByAddress(bytes.copyOfRange(12, 16)) else address
    }

    /** False for anything that is not a numeric `address/prefix`; never resolves a name. */
    fun isInCidr(address: InetAddress, cidr: String): Boolean {
        val network = cidr.substringBefore('/').trim()
        val prefix = cidr.substringAfter('/', "").trim().toIntOrNull() ?: return false
        if (!IPV4_LITERAL.matches(network) && !IPV6_LITERAL.matches(network)) return false
        val net = runCatching { unmapped(InetAddress.getByName(network)).address }.getOrNull() ?: return false
        val addr = unmapped(address).address
        if (net.size != addr.size || prefix < 0 || prefix > net.size * 8) return false
        val whole = prefix / 8
        for (i in 0 until whole) if (net[i] != addr[i]) return false
        val rest = prefix % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (net[whole].toInt() and mask) == (addr[whole].toInt() and mask)
    }

    private const val MAPPED: Byte = -1
    private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val IPV6_LITERAL = Regex("""^[0-9a-fA-F:.]*:[0-9a-fA-F:.]*$""")
}

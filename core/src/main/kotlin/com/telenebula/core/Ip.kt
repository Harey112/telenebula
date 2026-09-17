package com.telenebula.core

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
}

package com.telenebula.dex.http

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** One `address/prefix` network; [parse] answers null for anything it cannot read. */
class Cidr private constructor(private val network: ByteArray, private val prefixBits: Int) {
    fun contains(address: InetAddress): Boolean {
        val bytes = canonical(address) ?: return false
        if (bytes.size != network.size) return false
        val fullBytes = prefixBits / 8
        for (i in 0 until fullBytes) if (bytes[i] != network[i]) return false
        val rest = prefixBits % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (bytes[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }

    companion object {
        fun parse(text: String): Cidr? {
            val slash = text.indexOf('/')
            val host = (if (slash >= 0) text.substring(0, slash) else text).trim()
            if (host.isEmpty() || host.any { it.isLetter() && it !in 'a'..'f' && it !in 'A'..'F' }) return null
            val address = try {
                canonical(InetAddress.getByName(host)) ?: return null
            } catch (e: Exception) {
                return null
            }
            val maxBits = address.size * 8
            val bits = if (slash >= 0) text.substring(slash + 1).trim().toIntOrNull() ?: return null else maxBits
            if (bits !in 0..maxBits) return null
            return Cidr(address, bits)
        }

        fun anyContains(networks: List<Cidr>, address: InetAddress): Boolean = networks.any { it.contains(address) }

        /** IPv4-mapped IPv6 addresses compare as IPv4. */
        private fun canonical(address: InetAddress): ByteArray? = when (address) {
            is Inet4Address -> address.address
            is Inet6Address -> {
                val b = address.address
                val isMapped = (0 until 10).all { b[it] == 0.toByte() } && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
                if (isMapped) b.copyOfRange(12, 16) else b
            }
            else -> null
        }
    }
}

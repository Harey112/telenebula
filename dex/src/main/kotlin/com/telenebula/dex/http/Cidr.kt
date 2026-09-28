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
        /** The network [address] sits in with [prefixBits]; null for a prefix the family cannot have. */
        fun of(address: InetAddress, prefixBits: Int): Cidr? {
            val bytes = canonical(address) ?: return null
            if (prefixBits !in 0..bytes.size * 8) return null
            return Cidr(bytes, prefixBits)
        }

        /** An IP literal, never a name to resolve; null for anything else. */
        fun literal(text: String): InetAddress? {
            val host = text.trim()
            val isV6 = ':' in host && host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
            val isV4 = host.split('.').let { parts -> parts.size == 4 && parts.all { p -> p.length in 1..3 && p.all { it in '0'..'9' } } }
            if (!isV6 && !isV4) return null
            return try {
                InetAddress.getByName(host)
            } catch (e: Exception) {
                null
            }
        }

        fun sameAddress(a: InetAddress, b: InetAddress): Boolean {
            val x = canonical(a) ?: return false
            val y = canonical(b) ?: return false
            return x.contentEquals(y)
        }

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

package com.telenebula.calls

import java.net.InetAddress

/** The fields of an ICE candidate line (RFC 8839 §5.1) the engine decides on. */
internal object IceCandidates {
    private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val IPV6_LITERAL = Regex("""^[0-9a-fA-F:.]*:[0-9a-fA-F:.]*$""")

    fun address(candidate: String): String? = candidate.split(' ').getOrNull(4)

    fun type(candidate: String): String? {
        val parts = candidate.split(' ')
        return parts.indexOf("typ").takeIf { it >= 0 }?.let { parts.getOrNull(it + 1) }
    }

    fun isOnAddress(candidate: String, ip: String): Boolean {
        val mine = literal(address(candidate) ?: return false) ?: return false
        return mine == literal(ip)
    }

    // getByName resolves anything that is not a literal through DNS; a peer-supplied name must never reach it
    private fun literal(text: String): InetAddress? {
        val bare = text.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
        if (!IPV4_LITERAL.matches(bare) && !IPV6_LITERAL.matches(bare)) return null
        return runCatching { InetAddress.getByName(bare) }.getOrNull()
    }
}

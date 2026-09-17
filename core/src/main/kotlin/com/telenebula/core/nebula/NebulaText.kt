package com.telenebula.core.nebula

/**
 * The shape checks behind every nebula field. They are hand-written rather than regex- or
 * `InetAddress`-based: `InetAddress.getByName` resolves names over the network, which a
 * validator must never do, and the messages below are part of the editor's contract.
 */
internal object NebulaText {
    fun isDigits(s: String): Boolean = s.isNotEmpty() && s.all { it in '0'..'9' }

    /** `^-?\d+$` */
    fun isInteger(s: String): Boolean = isDigits(s.removePrefix("-"))

    /** `^\d+(ms|s|m|h)$` */
    fun isDuration(s: String): Boolean {
        val body = when {
            s.endsWith("ms") -> s.dropLast(2)
            s.endsWith("s") || s.endsWith("m") || s.endsWith("h") -> s.dropLast(1)
            else -> return false
        }
        return isDigits(body)
    }

    /** `^[0-9a-f]{64}$`, case-insensitive */
    fun isFingerprint(s: String): Boolean = s.length == 64 && s.all { it.isHex() }

    /** `^[a-z0-9.-]+$`, case-insensitive */
    fun isHostName(s: String): Boolean =
        s.isNotEmpty() && s.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '.' || it == '-' }

    private fun isPortDigits(s: String): Boolean = s.length in 1..5 && isDigits(s)

    /** `^(any|fragment|\d{1,5}|\d{1,5}-\d{1,5})$` */
    fun isPortSpec(s: String): Boolean {
        if (s == "any" || s == "fragment") return true
        val dash = s.indexOf('-')
        if (dash < 0) return isPortDigits(s)
        return isPortDigits(s.substring(0, dash)) && isPortDigits(s.substring(dash + 1))
    }

    fun isIpAddress(value: String): Boolean {
        val text = value.trim()
        return isIpv4(text) || isIpv6(text)
    }

    fun isCidr(value: String): Boolean {
        val text = value.trim()
        val slash = text.indexOf('/')
        if (slash < 0) return false
        val host = text.substring(0, slash)
        val prefix = text.substring(slash + 1)
        if (!isDigits(prefix)) return false
        val bits = prefix.toIntOrNull() ?: return false
        return when {
            isIpv4(host) -> bits <= 32
            isIpv6(host) -> bits <= 128
            else -> false
        }
    }

    /** host:port, [v6]:port or name:port */
    fun isHostPort(value: String): Boolean {
        val text = value.trim()
        val colon = text.lastIndexOf(':')
        if (colon < 0) return false
        val host = text.substring(0, colon)
        val port = text.substring(colon + 1)
        if (!isPortDigits(port) || (port.toIntOrNull() ?: return false) > 65535) return false
        val bracketedV6 = host.length > 2 && host.startsWith('[') && host.endsWith(']') &&
            host.substring(1, host.length - 1).all { it.isHex() || it == ':' }
        return bracketedV6 || (host.isNotEmpty() && !host.contains(':') && host.none { it.isWhitespace() })
    }

    /** Non-empty, trimmed lines. */
    fun lines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Non-empty, trimmed comma-separated parts. */
    fun commaList(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            isDigits(part) && part.length <= 3 &&
                // a leading zero is not a valid octet: "010.1.1.1" is not an address
                (part.length == 1 || part[0] != '0') &&
                (part.toIntOrNull() ?: 256) <= 255
        }
    }

    private fun isIpv6(s: String): Boolean {
        // a zone id belongs to a socket address, not to the literal the config stores
        if (s.isEmpty() || s.contains('%')) return false
        val compressed = s.indexOf("::")
        if (compressed < 0) return groupsOf(s.split(':'), allowIpv4Tail = true) == 8
        if (s.indexOf("::", compressed + 2) >= 0) return false
        val headText = s.substring(0, compressed)
        val tailText = s.substring(compressed + 2)
        if (headText.endsWith(':') || tailText.startsWith(':')) return false
        val headParts = if (headText.isEmpty()) emptyList() else headText.split(':')
        val tailParts = if (tailText.isEmpty()) emptyList() else tailText.split(':')
        val head = groupsOf(headParts, allowIpv4Tail = tailParts.isEmpty())
        val tail = groupsOf(tailParts, allowIpv4Tail = true)
        if (head < 0 || tail < 0) return false
        // "::" stands for at least one zero group, so the written ones can never fill all eight
        return head + tail <= 7
    }

    /** 16-bit groups in `parts`, or -1 when one of them is not a group. */
    private fun groupsOf(parts: List<String>, allowIpv4Tail: Boolean): Int {
        if (parts.isEmpty()) return 0
        var count = 0
        for ((index, part) in parts.withIndex()) {
            val isLast = index == parts.size - 1
            if (isLast && allowIpv4Tail && part.contains('.')) {
                if (!isIpv4(part)) return -1
                count += 2
            } else {
                if (part.isEmpty() || part.length > 4 || !part.all { it.isHex() }) return -1
                count += 1
            }
        }
        return count
    }
}

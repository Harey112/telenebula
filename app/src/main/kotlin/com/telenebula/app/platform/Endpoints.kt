package com.telenebula.app.platform

object Endpoints {
    class Split(val host: String, val port: String)

    /** `host:4242` or `[fd00::1]:4242`; a value with no numeric port comes back whole as the host. */
    fun split(underlay: String): Split {
        val text = underlay.trim()
        if (text.startsWith("[")) {
            val end = text.indexOf("]:")
            if (end > 0) return Split(text.substring(1, end), text.substring(end + 2))
        }
        val colon = text.lastIndexOf(':')
        if (colon > 0 && text.substring(colon + 1).all { it.isDigit() } && colon == text.indexOf(':')) {
            return Split(text.substring(0, colon), text.substring(colon + 1))
        }
        return Split(text, "")
    }

    /** A bare IPv6 host is bracketed, as nebula's static host map expects. */
    fun join(host: String, port: String): String {
        val h = host.trim()
        return if (h.contains(':') && !h.startsWith("[")) "[$h]:${port.trim()}" else "$h:${port.trim()}"
    }

    fun isValidPort(port: String): Boolean = port.trim().toIntOrNull()?.let { it in 1..65535 } == true

    fun isValid(host: String, port: String): Boolean = host.isNotBlank() && ' ' !in host.trim() && isValidPort(port)
}

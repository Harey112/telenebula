package com.telenebula.dex.http

/** The host and effective port a request names; [host] is lower-cased, an IPv6 literal kept in its brackets. */
data class Authority(val host: String, val port: Int) {
    /** How a browser writes it in an Origin: the port only when it is not the scheme's own. */
    val text: String get() = if (port == HTTPS_PORT) host else "$host:$port"

    companion object {
        const val HTTPS_PORT = 443
    }
}

/** One well-formed Host naming the port the connection reached, and for anything that acts or upgrades, that same https Origin. */
object OriginPolicy {
    private val LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private val IPV6_CHARS = Regex("[0-9a-f:.]+")
    private const val MAX_HOST_CHARS = 253

    /** The request's authority, or null when Host is missing, repeated, malformed or names another port. */
    fun authorityOf(request: HttpRequest, localPort: Int): Authority? {
        val authority = parseAuthority(request.single("host") ?: return null) ?: return null
        return authority.takeIf { it.port == localPort }
    }

    fun parseAuthority(raw: String): Authority? {
        val text = raw.trim().lowercase()
        if (text.isEmpty() || text.length > MAX_HOST_CHARS + 8) return null
        val host: String
        val portText: String?
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0) return null
            host = text.substring(0, close + 1)
            val rest = text.substring(close + 1)
            portText = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
            if (!isIpv6Literal(host.substring(1, host.length - 1))) return null
        } else {
            val colon = text.lastIndexOf(':')
            host = if (colon >= 0) text.substring(0, colon) else text
            portText = if (colon >= 0) text.substring(colon + 1) else null
            if (!isDnsName(host) && !isIpv4Literal(host)) return null
        }
        val port = if (portText == null) Authority.HTTPS_PORT else parsePort(portText) ?: return null
        return Authority(host, port)
    }

    /** An exact `https://host[:port]` naming [authority]; `null`, other schemes, paths and a second Origin are refused. */
    fun isSameOrigin(origin: String?, authority: Authority): Boolean {
        val text = origin?.trim() ?: return false
        if (!text.startsWith("https://")) return false
        val parsed = parseAuthority(text.removePrefix("https://")) ?: return false
        return parsed == authority
    }

    /** A WebSocket upgrade: the browser always sends Origin, so one that is absent or foreign is refused. */
    fun mayUpgrade(request: HttpRequest, authority: Authority): Boolean =
        isSameOrigin(request.single("origin"), authority) && isSameSiteFetch(request, allowAbsent = true, allowUserTyped = false)

    /** A cookie-authenticated POST: Origin must match, or, where a browser leaves it out, Fetch Metadata must say same-origin. */
    fun mayAct(request: HttpRequest, authority: Authority): Boolean {
        if (request.header("origin") != null) return isSameOrigin(request.single("origin"), authority) && isSameSiteFetch(request, allowAbsent = true, allowUserTyped = false)
        return isSameSiteFetch(request, allowAbsent = false, allowUserTyped = false)
    }

    /** A cookie-authenticated read: refused only when the browser says another site asked for it. */
    fun mayRead(request: HttpRequest, authority: Authority): Boolean {
        if (request.header("origin") != null && !isSameOrigin(request.single("origin"), authority)) return false
        return isSameSiteFetch(request, allowAbsent = true, allowUserTyped = true)
    }

    /** `none` is a navigation the person started themselves, which only a read may be. */
    private fun isSameSiteFetch(request: HttpRequest, allowAbsent: Boolean, allowUserTyped: Boolean): Boolean {
        if (request.header("sec-fetch-site") == null) return allowAbsent
        val site = request.single("sec-fetch-site")?.trim()?.lowercase()
        return site == "same-origin" || (allowUserTyped && site == "none")
    }

    private fun parsePort(text: String): Int? {
        if (text.isEmpty() || text.length > 5 || !text.all { it in '0'..'9' }) return null
        return text.toInt().takeIf { it in 1..65535 }
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { p -> p.length in 1..3 && p.all { it in '0'..'9' } && p.toInt() <= 255 && (p.length == 1 || p[0] != '0') }
    }

    private fun isDnsName(host: String): Boolean {
        if (host.isEmpty() || host.length > MAX_HOST_CHARS) return false
        val labels = host.removeSuffix(".").split('.')
        if (labels.any { !LABEL.matches(it) }) return false
        return !labels.last().all { it in '0'..'9' }
    }

    private fun isIpv6Literal(inner: String): Boolean {
        if (inner.isEmpty() || !IPV6_CHARS.matches(inner) || ':' !in inner) return false
        return try {
            java.net.InetAddress.getByName(inner)
            true
        } catch (e: java.net.UnknownHostException) {
            false
        }
    }
}

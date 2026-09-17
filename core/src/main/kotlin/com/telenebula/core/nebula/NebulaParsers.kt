package com.telenebula.core.nebula

import com.telenebula.core.model.NebulaAllowRule
import com.telenebula.core.model.NebulaCalculatedRemote
import com.telenebula.core.model.NebulaRouteMtu
import com.telenebula.core.model.NebulaSshUser
import com.telenebula.core.model.NebulaStaticHost

internal object NebulaParsers {
    fun renderAllowRule(rule: NebulaAllowRule): String = "${rule.target} = ${rule.isAllowed}"

    fun checkedLines(raw: String, isValid: (String) -> Boolean, message: String): Parse<List<String>> {
        val lines = NebulaText.lines(raw)
        val bad = lines.firstOrNull { !isValid(it) }
        return if (bad != null) Parse.Invalid("$message: $bad") else Parse.Ok(lines)
    }

    fun allowLines(raw: String): Parse<List<NebulaAllowRule>> {
        val out = ArrayList<NebulaAllowRule>()
        for (line in NebulaText.lines(raw)) {
            val parts = line.split('=').map { it.trim() }
            val target = parts.firstOrNull().orEmpty()
            val isAllowed = when (parts.getOrNull(1)) {
                "true" -> true
                "false" -> false
                else -> return Parse.Invalid("Use \"target = true|false\": $line")
            }
            if (target.isEmpty() || parts.size > 2) return Parse.Invalid("Use \"target = true|false\": $line")
            if (target.contains('/') && !NebulaText.isCidr(target)) return Parse.Invalid("Not a CIDR: $target")
            out += NebulaAllowRule(target = target, isAllowed = isAllowed)
        }
        return Parse.Ok(out)
    }

    fun staticHostLines(raw: String): Parse<List<NebulaStaticHost>> {
        val out = ArrayList<NebulaStaticHost>()
        for (line in NebulaText.lines(raw)) {
            val parts = line.split('=').map { it.trim() }
            val ip = parts.firstOrNull().orEmpty()
            val rest = parts.getOrNull(1).orEmpty()
            if (ip.isEmpty() || rest.isEmpty() || parts.size > 2 || !NebulaText.isIpAddress(ip)) {
                return Parse.Invalid("Use \"nebula ip = endpoint, endpoint\": $line")
            }
            val underlays = NebulaText.commaList(rest)
            val bad = underlays.firstOrNull { !NebulaText.isHostPort(it) }
            when {
                bad != null -> return Parse.Invalid("Not host:port: $bad")
                underlays.isEmpty() -> return Parse.Invalid("Not host:port: $line")
                else -> out += NebulaStaticHost(nebulaIp = ip, underlays = underlays)
            }
        }
        return Parse.Ok(out)
    }

    fun calculatedRemoteLines(raw: String): Parse<List<NebulaCalculatedRemote>> {
        val out = ArrayList<NebulaCalculatedRemote>()
        for (line in NebulaText.lines(raw)) {
            val parts = line.split(',').map { it.trim() }
            val cidr = parts.firstOrNull().orEmpty()
            val mask = parts.getOrNull(1).orEmpty()
            val port = parts.getOrNull(2).orEmpty()
            if (cidr.isEmpty() || mask.isEmpty() || port.isEmpty() || parts.size > 3 ||
                !NebulaText.isCidr(cidr) || !NebulaText.isCidr(mask)
            ) {
                return Parse.Invalid("Use \"cidr, mask cidr, port\": $line")
            }
            val parsedPort = port.toIntOrNull()?.takeIf { NebulaText.isDigits(port) && it <= 65535 }
                ?: return Parse.Invalid("Bad port: $port")
            out += NebulaCalculatedRemote(cidr = cidr, mask = mask, port = parsedPort)
        }
        return Parse.Ok(out)
    }

    fun routeMtuLines(raw: String): Parse<List<NebulaRouteMtu>> {
        val out = ArrayList<NebulaRouteMtu>()
        for (line in NebulaText.lines(raw)) {
            val parts = line.split(',').map { it.trim() }
            val route = parts.firstOrNull().orEmpty()
            val mtu = parts.getOrNull(1).orEmpty()
            val parsedMtu = mtu.toIntOrNull()
            if (route.isEmpty() || mtu.isEmpty() || parts.size > 2 ||
                !NebulaText.isCidr(route) || !NebulaText.isDigits(mtu) || parsedMtu == null
            ) {
                return Parse.Invalid("Use \"cidr, mtu\": $line")
            }
            out += NebulaRouteMtu(route = route, mtu = parsedMtu)
        }
        return Parse.Ok(out)
    }

    fun sshUserLines(raw: String): Parse<List<NebulaSshUser>> {
        val out = ArrayList<NebulaSshUser>()
        for (line in NebulaText.lines(raw)) {
            val colon = line.indexOf(':')
            if (colon <= 0) return Parse.Invalid("Use \"user: key, key\": $line")
            val user = line.substring(0, colon).trim()
            val keys = NebulaText.commaList(line.substring(colon + 1))
            if (keys.isEmpty()) return Parse.Invalid("No key for $user")
            out += NebulaSshUser(user = user, keys = keys)
        }
        return Parse.Ok(out)
    }
}

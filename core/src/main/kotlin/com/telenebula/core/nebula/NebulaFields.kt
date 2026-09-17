package com.telenebula.core.nebula

import com.telenebula.core.model.FieldKind
import com.telenebula.core.model.NebulaAdvancedConfig
import com.telenebula.core.model.NebulaFieldSpec
import com.telenebula.core.model.NebulaSectionSpec
import com.telenebula.core.model.NebulaSelectOption
import com.telenebula.core.model.SectionEditor
import com.telenebula.core.model.ShowWhen
import com.telenebula.core.model.ShowWhenValue

private typealias Cfg = NebulaAdvancedConfig

/** One field's text either parses into a config or explains why it does not. */
internal sealed interface Parse<out T> {
    data class Ok<T>(val value: T) : Parse<T>

    data class Invalid(val message: String) : Parse<Nothing>
}

/**
 * Reads one field out of the config and writes a draft's text back into it. The lens is written
 * out per field rather than derived from the path, so the editor stays typed end to end: a
 * renamed config field is a compile error here, not a silently empty text box.
 */
internal sealed interface FieldBinding {
    class Flag(val read: (Cfg) -> Boolean, val write: (Cfg, Boolean) -> Cfg) : FieldBinding

    class Value(val read: (Cfg) -> String, val write: (Cfg, String) -> Parse<Cfg>) : FieldBinding
}

internal class FieldEntry(val spec: NebulaFieldSpec, val binding: FieldBinding)

private class SectionEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val footnote: String? = null,
    val fields: List<FieldEntry> = emptyList(),
    val editor: SectionEditor? = null,
)

// --- builders ---

private fun switchField(
    path: String,
    label: String,
    helper: String? = null,
    showWhen: ShowWhen? = null,
    read: (Cfg) -> Boolean,
    write: (Cfg, Boolean) -> Cfg,
) = FieldEntry(
    NebulaFieldSpec(path = path, label = label, helper = helper, isSwitch = true, showWhen = showWhen),
    FieldBinding.Flag(read, write),
)

private fun selectField(
    path: String,
    label: String,
    options: List<NebulaSelectOption>,
    showWhen: ShowWhen? = null,
    read: (Cfg) -> String,
    write: (Cfg, String) -> Cfg,
) = FieldEntry(
    NebulaFieldSpec(path = path, label = label, options = options, showWhen = showWhen),
    FieldBinding.Value(read) { config, text ->
        // an unknown or missing choice falls back to the first option rather than failing
        val key = if (options.any { it.key == text }) text else options.firstOrNull()?.key
        Parse.Ok(if (key == null) config else write(config, key))
    },
)

private fun textField(
    path: String,
    label: String,
    helper: String? = null,
    placeholder: String? = null,
    showWhen: ShowWhen? = null,
    read: (Cfg) -> String,
    write: (Cfg, String) -> Cfg,
) = FieldEntry(
    NebulaFieldSpec(
        path = path,
        label = label,
        helper = helper,
        placeholder = placeholder,
        kind = FieldKind.TEXT,
        showWhen = showWhen,
    ),
    FieldBinding.Value(read) { config, text -> Parse.Ok(write(config, text.trim())) },
)

private fun checkedField(
    path: String,
    label: String,
    kind: FieldKind,
    message: String,
    isValid: (String) -> Boolean,
    helper: String? = null,
    placeholder: String? = null,
    showWhen: ShowWhen? = null,
    read: (Cfg) -> String,
    write: (Cfg, String) -> Cfg,
) = FieldEntry(
    NebulaFieldSpec(
        path = path,
        label = label,
        helper = helper,
        placeholder = placeholder,
        kind = kind,
        showWhen = showWhen,
    ),
    FieldBinding.Value(read) { config, text ->
        val raw = text.trim()
        if (isValid(raw)) Parse.Ok(write(config, raw)) else Parse.Invalid(message)
    },
)

private fun durationField(
    path: String,
    label: String,
    placeholder: String,
    showWhen: ShowWhen? = null,
    read: (Cfg) -> String,
    write: (Cfg, String) -> Cfg,
) = checkedField(
    path = path,
    label = label,
    kind = FieldKind.DURATION,
    message = "Use a duration like 250ms, 1s, 10m or 1h",
    isValid = NebulaText::isDuration,
    placeholder = placeholder,
    showWhen = showWhen,
    read = read,
    write = write,
)

private fun hostField(
    path: String,
    label: String,
    placeholder: String? = null,
    read: (Cfg) -> String,
    write: (Cfg, String) -> Cfg,
) = checkedField(
    path = path,
    label = label,
    kind = FieldKind.HOST,
    message = "Enter an IP address or host name",
    isValid = { it.isNotEmpty() && (NebulaText.isIpAddress(it) || NebulaText.isHostName(it)) },
    placeholder = placeholder,
    read = read,
    write = write,
)

private fun hostPortField(
    path: String,
    label: String,
    showWhen: ShowWhen? = null,
    read: (Cfg) -> String,
    write: (Cfg, String) -> Cfg,
) = checkedField(
    path = path,
    label = label,
    kind = FieldKind.HOSTPORT,
    message = "Use host:port",
    isValid = NebulaText::isHostPort,
    showWhen = showWhen,
    read = read,
    write = write,
)

private fun numberField(
    path: String,
    label: String,
    min: Int? = null,
    max: Int? = null,
    read: (Cfg) -> Int,
    write: (Cfg, Int) -> Cfg,
) = FieldEntry(
    NebulaFieldSpec(path = path, label = label, kind = FieldKind.NUMBER, min = min, max = max),
    FieldBinding.Value({ read(it).toString() }) { config, text ->
        val raw = text.trim()
        val value = if (NebulaText.isInteger(raw)) raw.toIntOrNull() else null
        when {
            value == null -> Parse.Invalid("Enter a whole number")
            min != null && value < min -> Parse.Invalid("Minimum is $min")
            max != null && value > max -> Parse.Invalid("Maximum is $max")
            else -> Parse.Ok(write(config, value))
        }
    },
)

private fun <T> linesField(
    path: String,
    label: String,
    kind: FieldKind,
    placeholder: String? = null,
    showWhen: ShowWhen? = null,
    read: (Cfg) -> List<T>,
    render: (T) -> String,
    parse: (String) -> Parse<List<T>>,
    write: (Cfg, List<T>) -> Cfg,
) = FieldEntry(
    NebulaFieldSpec(path = path, label = label, placeholder = placeholder, kind = kind, showWhen = showWhen),
    FieldBinding.Value({ read(it).joinToString("\n", transform = render) }) { config, text ->
        when (val parsed = parse(text.trim())) {
            is Parse.Ok -> Parse.Ok(write(config, parsed.value))
            is Parse.Invalid -> parsed
        }
    },
)

/** A plain list of strings, each line checked by [isValid]. */
private fun stringLinesField(
    path: String,
    label: String,
    kind: FieldKind,
    message: String,
    isValid: (String) -> Boolean,
    placeholder: String? = null,
    showWhen: ShowWhen? = null,
    read: (Cfg) -> List<String>,
    write: (Cfg, List<String>) -> Cfg,
) = linesField(
    path = path,
    label = label,
    kind = kind,
    placeholder = placeholder,
    showWhen = showWhen,
    read = read,
    render = { it },
    parse = { raw -> NebulaParsers.checkedLines(raw, isValid, message) },
    write = write,
)

private fun option(key: String, label: String) = NebulaSelectOption(key, label)

private fun whenOn(path: String) = ShowWhen(path, ShowWhenValue.Flag(true))

private fun whenChoice(path: String, key: String) = ShowWhen(path, ShowWhenValue.Choice(key))

private val RECV_ERROR_OPTIONS =
    listOf(option("always", "Always"), option("never", "Never"), option("private", "Private remotes"))

private val ACTION_OPTIONS = listOf(option("drop", "Drop"), option("reject", "Reject"))

// --- the table: section order and every field, driving the generic editor ---

private val SECTION_TABLE: List<SectionEntry> = listOf(
    SectionEntry(
        key = "pki",
        title = "PKI",
        subtitle = "Blocklist, invalid certificates",
        footnote = "CA, certificate and key come from setup. A v2 initiating version only matters once every host carries both certificate versions.",
        fields = listOf(
            stringLinesField(
                path = "pki.blocklist",
                label = "Blocklist (certificate fingerprints, one per line)",
                kind = FieldKind.FINGERPRINT_LINES,
                message = "Not a 64-hex fingerprint",
                isValid = NebulaText::isFingerprint,
                placeholder = "c99d4e65…36c72",
                read = { it.pki.blocklist },
                write = { c, v -> c.copy(pki = c.pki.copy(blocklist = v)) },
            ),
            switchField(
                path = "pki.disconnectInvalid",
                label = "Disconnect invalid certificates",
                helper = "Drop tunnels whose certificate expired or became invalid",
                read = { it.pki.disconnectInvalid },
                write = { c, v -> c.copy(pki = c.pki.copy(disconnectInvalid = v)) },
            ),
            selectField(
                path = "pki.initiatingVersion",
                label = "Initiating certificate version",
                options = listOf(option("1", "v1"), option("2", "v2")),
                read = { it.pki.initiatingVersion.toString() },
                write = { c, v -> c.copy(pki = c.pki.copy(initiatingVersion = v.toIntOrNull() ?: 1)) },
            ),
        ),
    ),
    SectionEntry(
        key = "staticHosts",
        title = "Static hosts",
        subtitle = "Fixed addresses besides the lighthouse, DNS re-query",
        footnote = "The lighthouse above is always in static_host_map; list additional hosts here.",
        fields = listOf(
            linesField(
                path = "staticHosts",
                label = "Extra static hosts (nebula IP = endpoint, endpoint; one per line)",
                kind = FieldKind.STATIC_HOST_LINES,
                placeholder = "fd00:1234:5678::9 = 203.0.113.9:4242, relay.example.com:4242",
                read = { it.staticHosts },
                render = { "${it.nebulaIp} = ${it.underlays.joinToString(", ")}" },
                parse = NebulaParsers::staticHostLines,
                write = { c, v -> c.copy(staticHosts = v) },
            ),
            durationField(
                path = "staticMap.cadence",
                label = "DNS re-query cadence",
                placeholder = "30s",
                read = { it.staticMap.cadence },
                write = { c, v -> c.copy(staticMap = c.staticMap.copy(cadence = v)) },
            ),
            selectField(
                path = "staticMap.network",
                label = "DNS address family",
                options = listOf(option("ip4", "IPv4"), option("ip6", "IPv6"), option("ip", "Both")),
                read = { it.staticMap.network },
                write = { c, v -> c.copy(staticMap = c.staticMap.copy(network = v)) },
            ),
            durationField(
                path = "staticMap.lookupTimeout",
                label = "DNS lookup timeout",
                placeholder = "250ms",
                read = { it.staticMap.lookupTimeout },
                write = { c, v -> c.copy(staticMap = c.staticMap.copy(lookupTimeout = v)) },
            ),
        ),
    ),
    SectionEntry(
        key = "lighthouse",
        title = "Lighthouse",
        subtitle = "Report interval, allow lists, advertised addresses",
        footnote = "This device is never a lighthouse (am_lighthouse stays off). Allow lists take \"cidr = true|false\"; the most specific rule wins. In the local list an entry without \"/\" is an interface name pattern.",
        fields = listOf(
            numberField(
                path = "lighthouse.interval",
                label = "Report interval (seconds)",
                min = 1,
                max = 3600,
                read = { it.lighthouse.interval },
                write = { c, v -> c.copy(lighthouse = c.lighthouse.copy(interval = v)) },
            ),
            linesField(
                path = "lighthouse.remoteAllowList",
                label = "Remote allow list",
                kind = FieldKind.ALLOW_LINES,
                placeholder = "172.16.0.0/12 = false",
                read = { it.lighthouse.remoteAllowList },
                render = NebulaParsers::renderAllowRule,
                parse = NebulaParsers::allowLines,
                write = { c, v -> c.copy(lighthouse = c.lighthouse.copy(remoteAllowList = v)) },
            ),
            linesField(
                path = "lighthouse.localAllowList",
                label = "Local allow list",
                kind = FieldKind.ALLOW_LINES,
                placeholder = "tun0 = false",
                read = { it.lighthouse.localAllowList },
                render = NebulaParsers::renderAllowRule,
                parse = NebulaParsers::allowLines,
                write = { c, v -> c.copy(lighthouse = c.lighthouse.copy(localAllowList = v)) },
            ),
            stringLinesField(
                path = "lighthouse.advertiseAddrs",
                label = "Advertised addresses (ip:port, port 0 = listening port)",
                kind = FieldKind.HOSTPORT_LINES,
                message = "Not host:port",
                isValid = NebulaText::isHostPort,
                placeholder = "1.2.3.4:0",
                read = { it.lighthouse.advertiseAddrs },
                write = { c, v -> c.copy(lighthouse = c.lighthouse.copy(advertiseAddrs = v)) },
            ),
            linesField(
                path = "lighthouse.calculatedRemotes",
                label = "Calculated remotes (experimental: nebula cidr, mask cidr, port)",
                kind = FieldKind.CALCULATED_REMOTE_LINES,
                placeholder = "10.0.10.0/24, 192.168.1.0/24, 4242",
                read = { it.lighthouse.calculatedRemotes },
                render = { "${it.cidr}, ${it.mask}, ${it.port}" },
                parse = NebulaParsers::calculatedRemoteLines,
                write = { c, v -> c.copy(lighthouse = c.lighthouse.copy(calculatedRemotes = v)) },
            ),
        ),
    ),
    SectionEntry(
        key = "listen",
        title = "Listen",
        subtitle = "UDP socket, buffers, recv_error",
        footnote = "Port 0 picks a random port on every start, which the nebula authors recommend for roaming devices. Host, port, batch and offloads apply on the next tunnel start.",
        fields = listOf(
            hostField(
                path = "listen.host",
                label = "Bind host",
                placeholder = "::",
                read = { it.listen.host },
                write = { c, v -> c.copy(listen = c.listen.copy(host = v)) },
            ),
            numberField(
                path = "listen.port",
                label = "UDP port (0 = random)",
                min = 0,
                max = 65535,
                read = { it.listen.port },
                write = { c, v -> c.copy(listen = c.listen.copy(port = v)) },
            ),
            numberField(
                path = "listen.batch",
                label = "Batch (packets per syscall)",
                min = 1,
                max = 1024,
                read = { it.listen.batch },
                write = { c, v -> c.copy(listen = c.listen.copy(batch = v)) },
            ),
            numberField(
                path = "listen.readBuffer",
                label = "Read buffer bytes (0 = system default)",
                min = 0,
                read = { it.listen.readBuffer },
                write = { c, v -> c.copy(listen = c.listen.copy(readBuffer = v)) },
            ),
            numberField(
                path = "listen.writeBuffer",
                label = "Write buffer bytes (0 = system default)",
                min = 0,
                read = { it.listen.writeBuffer },
                write = { c, v -> c.copy(listen = c.listen.copy(writeBuffer = v)) },
            ),
            selectField(
                path = "listen.sendRecvError",
                label = "Send recv_error",
                options = RECV_ERROR_OPTIONS,
                read = { it.listen.sendRecvError },
                write = { c, v -> c.copy(listen = c.listen.copy(sendRecvError = v)) },
            ),
            selectField(
                path = "listen.acceptRecvError",
                label = "Accept recv_error",
                options = RECV_ERROR_OPTIONS,
                read = { it.listen.acceptRecvError },
                write = { c, v -> c.copy(listen = c.listen.copy(acceptRecvError = v)) },
            ),
            switchField(
                path = "listen.udpOffloads",
                label = "UDP offloads (GSO/GRO)",
                helper = "Linux only; leave off unless you know the kernel supports it",
                read = { it.listen.udpOffloads },
                write = { c, v -> c.copy(listen = c.listen.copy(udpOffloads = v)) },
            ),
            numberField(
                path = "routines",
                label = "Routines (tun/UDP reader pairs)",
                min = 1,
                max = 16,
                read = { it.routines },
                write = { c, v -> c.copy(routines = v) },
            ),
        ),
    ),
    SectionEntry(
        key = "punchy",
        title = "NAT punching",
        subtitle = "Hole punching, responses, delays",
        fields = listOf(
            switchField(
                path = "punchy.punch",
                label = "Punch",
                helper = "Keep NAT mappings alive with periodic punches",
                read = { it.punchy.punch },
                write = { c, v -> c.copy(punchy = c.punchy.copy(punch = v)) },
            ),
            switchField(
                path = "punchy.respond",
                label = "Respond",
                helper = "Connect back out when the other side cannot punch through",
                read = { it.punchy.respond },
                write = { c, v -> c.copy(punchy = c.punchy.copy(respond = v)) },
            ),
            durationField(
                path = "punchy.delay",
                label = "Punch delay",
                placeholder = "1s",
                read = { it.punchy.delay },
                write = { c, v -> c.copy(punchy = c.punchy.copy(delay = v)) },
            ),
            durationField(
                path = "punchy.respondDelay",
                label = "Respond delay",
                placeholder = "5s",
                showWhen = whenOn("punchy.respond"),
                read = { it.punchy.respondDelay },
                write = { c, v -> c.copy(punchy = c.punchy.copy(respondDelay = v)) },
            ),
        ),
    ),
    SectionEntry(
        key = "crypto",
        title = "Cipher and ranges",
        subtitle = "Cipher, preferred local ranges",
        footnote = "The cipher must be identical on every node and lighthouse of the network.",
        fields = listOf(
            selectField(
                path = "cipher",
                label = "Cipher",
                options = listOf(option("aes", "AES"), option("chachapoly", "ChaCha20-Poly1305")),
                read = { it.cipher },
                write = { c, v -> c.copy(cipher = v) },
            ),
            stringLinesField(
                path = "preferredRanges",
                label = "Preferred ranges (CIDRs, one per line)",
                kind = FieldKind.CIDR_LINES,
                message = "Not a CIDR",
                isValid = NebulaText::isCidr,
                placeholder = "172.16.0.0/24",
                read = { it.preferredRanges },
                write = { c, v -> c.copy(preferredRanges = v) },
            ),
        ),
    ),
    SectionEntry(
        key = "relay",
        title = "Relay",
        subtitle = "Relays for peers behind hard NATs",
        footnote = "Leave the list empty to relay through your lighthouse (it must run with am_relay on). This device can also relay for others.",
        fields = listOf(
            stringLinesField(
                path = "relay.relays",
                label = "Relays (nebula IPs, one per line)",
                kind = FieldKind.IP_LINES,
                message = "Not an IP address",
                isValid = NebulaText::isIpAddress,
                placeholder = "fd00:1234:5678::1",
                read = { it.relay.relays },
                write = { c, v -> c.copy(relay = c.relay.copy(relays = v)) },
            ),
            switchField(
                path = "relay.useRelays",
                label = "Use relays",
                read = { it.relay.useRelays },
                write = { c, v -> c.copy(relay = c.relay.copy(useRelays = v)) },
            ),
            switchField(
                path = "relay.amRelay",
                label = "Act as a relay",
                read = { it.relay.amRelay },
                write = { c, v -> c.copy(relay = c.relay.copy(amRelay = v)) },
            ),
        ),
    ),
    SectionEntry(
        key = "tun",
        title = "Tunnel interface",
        subtitle = "MTU, broadcast, routes, unsafe routes",
        footnote = "Android provides the tun device, so dev, disabled, offloads and CPU pinning are fixed. Unsafe routes are also installed in the VPN so the OS sends them into the tunnel.",
        editor = SectionEditor.UNSAFE_ROUTES,
        fields = listOf(
            numberField(
                path = "tun.mtu",
                label = "MTU",
                min = 576,
                max = 9000,
                read = { it.tun.mtu },
                write = { c, v -> c.copy(tun = c.tun.copy(mtu = v)) },
            ),
            switchField(
                path = "tun.dropLocalBroadcast",
                label = "Drop local broadcast",
                read = { it.tun.dropLocalBroadcast },
                write = { c, v -> c.copy(tun = c.tun.copy(dropLocalBroadcast = v)) },
            ),
            switchField(
                path = "tun.dropMulticast",
                label = "Drop multicast",
                read = { it.tun.dropMulticast },
                write = { c, v -> c.copy(tun = c.tun.copy(dropMulticast = v)) },
            ),
            numberField(
                path = "tun.txQueue",
                label = "Transmit queue length",
                min = 1,
                read = { it.tun.txQueue },
                write = { c, v -> c.copy(tun = c.tun.copy(txQueue = v)) },
            ),
            linesField(
                path = "tun.routes",
                label = "Route MTU overrides (cidr, mtu; one per line)",
                kind = FieldKind.ROUTE_MTU_LINES,
                placeholder = "fd00:1234:5678::/64, 8800",
                read = { it.tun.routes },
                render = { "${it.route}, ${it.mtu}" },
                parse = NebulaParsers::routeMtuLines,
                write = { c, v -> c.copy(tun = c.tun.copy(routes = v)) },
            ),
        ),
    ),
    SectionEntry(
        key = "logging",
        title = "Logging",
        subtitle = "Format, timestamps",
        footnote = "The log level lives under Network → Nebula log level.",
        fields = listOf(
            selectField(
                path = "logging.format",
                label = "Format",
                options = listOf(option("text", "Text"), option("json", "JSON")),
                read = { it.logging.format },
                write = { c, v -> c.copy(logging = c.logging.copy(format = v)) },
            ),
            switchField(
                path = "logging.disableTimestamp",
                label = "Disable timestamps",
                read = { it.logging.disableTimestamp },
                write = { c, v -> c.copy(logging = c.logging.copy(disableTimestamp = v)) },
            ),
        ),
    ),
    SectionEntry(
        key = "stats",
        title = "Stats",
        subtitle = "Graphite or Prometheus metrics",
        fields = listOf(
            selectField(
                path = "stats.type",
                label = "Backend",
                options = listOf(option("none", "Off"), option("graphite", "Graphite"), option("prometheus", "Prometheus")),
                read = { it.stats.type },
                write = { c, v -> c.copy(stats = c.stats.copy(type = v)) },
            ),
            textField(
                path = "stats.prefix",
                label = "Prefix",
                showWhen = whenChoice("stats.type", "graphite"),
                read = { it.stats.prefix },
                write = { c, v -> c.copy(stats = c.stats.copy(prefix = v)) },
            ),
            textField(
                path = "stats.protocol",
                label = "Protocol (tcp/udp)",
                showWhen = whenChoice("stats.type", "graphite"),
                read = { it.stats.protocol },
                write = { c, v -> c.copy(stats = c.stats.copy(protocol = v)) },
            ),
            hostPortField(
                path = "stats.host",
                label = "Graphite host:port",
                showWhen = whenChoice("stats.type", "graphite"),
                read = { it.stats.host },
                write = { c, v -> c.copy(stats = c.stats.copy(host = v)) },
            ),
            hostPortField(
                path = "stats.listen",
                label = "Listen host:port",
                showWhen = whenChoice("stats.type", "prometheus"),
                read = { it.stats.listen },
                write = { c, v -> c.copy(stats = c.stats.copy(listen = v)) },
            ),
            textField(
                path = "stats.path",
                label = "Metrics path",
                showWhen = whenChoice("stats.type", "prometheus"),
                read = { it.stats.path },
                write = { c, v -> c.copy(stats = c.stats.copy(path = v)) },
            ),
            textField(
                path = "stats.namespace",
                label = "Namespace",
                showWhen = whenChoice("stats.type", "prometheus"),
                read = { it.stats.namespace },
                write = { c, v -> c.copy(stats = c.stats.copy(namespace = v)) },
            ),
            textField(
                path = "stats.subsystem",
                label = "Subsystem",
                showWhen = whenChoice("stats.type", "prometheus"),
                read = { it.stats.subsystem },
                write = { c, v -> c.copy(stats = c.stats.copy(subsystem = v)) },
            ),
            durationField(
                path = "stats.interval",
                label = "Interval",
                placeholder = "10s",
                read = { it.stats.interval },
                write = { c, v -> c.copy(stats = c.stats.copy(interval = v)) },
            ),
            switchField(
                path = "stats.messageMetrics",
                label = "Message metrics",
                read = { it.stats.messageMetrics },
                write = { c, v -> c.copy(stats = c.stats.copy(messageMetrics = v)) },
            ),
            switchField(
                path = "stats.lighthouseMetrics",
                label = "Lighthouse metrics",
                read = { it.stats.lighthouseMetrics },
                write = { c, v -> c.copy(stats = c.stats.copy(lighthouseMetrics = v)) },
            ),
        ),
    ),
    SectionEntry(
        key = "handshakes",
        title = "Handshakes and tunnels",
        subtitle = "Retry timing, buffers, inactive tunnels",
        fields = listOf(
            durationField(
                path = "handshakes.tryInterval",
                label = "Try interval",
                placeholder = "100ms",
                read = { it.handshakes.tryInterval },
                write = { c, v -> c.copy(handshakes = c.handshakes.copy(tryInterval = v)) },
            ),
            numberField(
                path = "handshakes.retries",
                label = "Retries",
                min = 1,
                max = 100,
                read = { it.handshakes.retries },
                write = { c, v -> c.copy(handshakes = c.handshakes.copy(retries = v)) },
            ),
            numberField(
                path = "handshakes.queryBuffer",
                label = "Query buffer",
                min = 1,
                read = { it.handshakes.queryBuffer },
                write = { c, v -> c.copy(handshakes = c.handshakes.copy(queryBuffer = v)) },
            ),
            numberField(
                path = "handshakes.triggerBuffer",
                label = "Trigger buffer",
                min = 1,
                read = { it.handshakes.triggerBuffer },
                write = { c, v -> c.copy(handshakes = c.handshakes.copy(triggerBuffer = v)) },
            ),
            switchField(
                path = "tunnels.dropInactive",
                label = "Drop inactive tunnels",
                read = { it.tunnels.dropInactive },
                write = { c, v -> c.copy(tunnels = c.tunnels.copy(dropInactive = v)) },
            ),
            durationField(
                path = "tunnels.inactivityTimeout",
                label = "Inactivity timeout",
                placeholder = "10m",
                showWhen = whenOn("tunnels.dropInactive"),
                read = { it.tunnels.inactivityTimeout },
                write = { c, v -> c.copy(tunnels = c.tunnels.copy(inactivityTimeout = v)) },
            ),
        ),
    ),
    SectionEntry(
        key = "firewall",
        title = "Firewall",
        subtitle = "Actions, connection tracking, rules",
        footnote = "Nebula firewalls are default-deny; rules only allow. The locked rules carry what TeleNebula itself needs: TCP on the message port for messaging and signaling, UDP for call media, ICMP for pings.",
        editor = SectionEditor.FIREWALL,
        fields = listOf(
            selectField(
                path = "firewall.inboundAction",
                label = "Inbound action for blocked packets",
                options = ACTION_OPTIONS,
                read = { it.firewall.inboundAction },
                write = { c, v -> c.copy(firewall = c.firewall.copy(inboundAction = v)) },
            ),
            selectField(
                path = "firewall.outboundAction",
                label = "Outbound action for blocked packets",
                options = ACTION_OPTIONS,
                read = { it.firewall.outboundAction },
                write = { c, v -> c.copy(firewall = c.firewall.copy(outboundAction = v)) },
            ),
            switchField(
                path = "firewall.defaultLocalCidrAny",
                label = "Rules apply to all unsafe routes (deprecated)",
                read = { it.firewall.defaultLocalCidrAny },
                write = { c, v -> c.copy(firewall = c.firewall.copy(defaultLocalCidrAny = v)) },
            ),
            durationField(
                path = "firewall.conntrack.tcpTimeout",
                label = "TCP conntrack timeout",
                placeholder = "12m",
                read = { it.firewall.conntrack.tcpTimeout },
                write = { c, v -> c.copy(firewall = c.firewall.copy(conntrack = c.firewall.conntrack.copy(tcpTimeout = v))) },
            ),
            durationField(
                path = "firewall.conntrack.udpTimeout",
                label = "UDP conntrack timeout",
                placeholder = "3m",
                read = { it.firewall.conntrack.udpTimeout },
                write = { c, v -> c.copy(firewall = c.firewall.copy(conntrack = c.firewall.conntrack.copy(udpTimeout = v))) },
            ),
            durationField(
                path = "firewall.conntrack.defaultTimeout",
                label = "Default conntrack timeout",
                placeholder = "10m",
                read = { it.firewall.conntrack.defaultTimeout },
                write = { c, v -> c.copy(firewall = c.firewall.copy(conntrack = c.firewall.conntrack.copy(defaultTimeout = v))) },
            ),
        ),
    ),
    SectionEntry(
        key = "sshd",
        title = "SSH debug console",
        subtitle = "Off by default",
        footnote = "Exposes nebula diagnostics over SSH. The host key is a file path on this device; port 22 is refused by nebula.",
        fields = listOf(
            switchField(
                path = "sshd.isEnabled",
                label = "Enable sshd",
                read = { it.sshd.isEnabled },
                write = { c, v -> c.copy(sshd = c.sshd.copy(isEnabled = v)) },
            ),
            hostPortField(
                path = "sshd.listen",
                label = "Listen host:port",
                showWhen = whenOn("sshd.isEnabled"),
                read = { it.sshd.listen },
                write = { c, v -> c.copy(sshd = c.sshd.copy(listen = v)) },
            ),
            textField(
                path = "sshd.hostKey",
                label = "Host key file path",
                showWhen = whenOn("sshd.isEnabled"),
                read = { it.sshd.hostKey },
                write = { c, v -> c.copy(sshd = c.sshd.copy(hostKey = v)) },
            ),
            linesField(
                path = "sshd.authorizedUsers",
                label = "Authorized users (user: key, key; one per line)",
                kind = FieldKind.SSH_USER_LINES,
                placeholder = "alice: ssh-ed25519 AAAA…",
                showWhen = whenOn("sshd.isEnabled"),
                read = { it.sshd.authorizedUsers },
                render = { "${it.user}: ${it.keys.joinToString(", ")}" },
                parse = NebulaParsers::sshUserLines,
                write = { c, v -> c.copy(sshd = c.sshd.copy(authorizedUsers = v)) },
            ),
            stringLinesField(
                path = "sshd.trustedCas",
                label = "Trusted SSH CA public keys (one per line)",
                kind = FieldKind.LINES,
                message = "",
                isValid = { true },
                showWhen = whenOn("sshd.isEnabled"),
                read = { it.sshd.trustedCas },
                write = { c, v -> c.copy(sshd = c.sshd.copy(trustedCas = v)) },
            ),
        ),
    ),
)

/** The section/field table the advanced editor renders, without the bindings behind it. */
internal val NEBULA_SECTIONS: List<NebulaSectionSpec> = SECTION_TABLE.map { section ->
    NebulaSectionSpec(
        key = section.key,
        title = section.title,
        subtitle = section.subtitle,
        footnote = section.footnote,
        fields = section.fields.map { it.spec },
        editor = section.editor,
    )
}

internal val NEBULA_FIELDS: List<FieldEntry> = SECTION_TABLE.flatMap { it.fields }


package com.telenebula.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FirewallProto {
    @SerialName("any") ANY,
    @SerialName("tcp") TCP,
    @SerialName("udp") UDP,
    @SerialName("icmp") ICMP,
}

/** which certificate attribute a rule matches; `value` holds it */
@Serializable
enum class FirewallMatch {
    @SerialName("host") HOST,
    @SerialName("group") GROUP,
    @SerialName("groups") GROUPS,
    @SerialName("cidr") CIDR,
}

@Serializable
data class FirewallRule(
    val id: String,
    val proto: FirewallProto = FirewallProto.ANY,
    /** any, 80, 200-901 or fragment */
    val port: String = "any",
    val match: FirewallMatch = FirewallMatch.HOST,
    val value: String = "any",
    val localCidr: String = "",
    val caName: String = "",
    val caSha: String = "",
)

@Serializable
data class UnsafeRoute(
    val id: String,
    val route: String = "",
    val via: String = "",
    /** empty = tun mtu */
    val mtu: String = "",
    /** empty = 0 */
    val metric: String = "",
    val isInstalled: Boolean = true,
)

// --- the advanced config: every option of nebula's own config.yml that applies to an Android node ---

@Serializable
data class NebulaPki(
    val blocklist: List<String> = emptyList(),
    val disconnectInvalid: Boolean = false,
    val initiatingVersion: Int = 1,
)

/** A fixed address besides the lighthouse (a relay, a server). */
@Serializable
data class NebulaStaticHost(val nebulaIp: String = "", val underlays: List<String> = emptyList())

@Serializable
data class NebulaStaticMap(
    val cadence: String = "30s",
    val network: String = "ip4",
    val lookupTimeout: String = "250ms",
)

/** One "cidr = true|false" line of an allow list. */
@Serializable
data class NebulaAllowRule(val target: String = "", val isAllowed: Boolean = false)

@Serializable
data class NebulaCalculatedRemote(val cidr: String = "", val mask: String = "", val port: Int = 0)

@Serializable
data class NebulaLighthouse(
    val interval: Int = 60,
    val remoteAllowList: List<NebulaAllowRule> = emptyList(),
    val localAllowList: List<NebulaAllowRule> = emptyList(),
    val advertiseAddrs: List<String> = emptyList(),
    val calculatedRemotes: List<NebulaCalculatedRemote> = emptyList(),
)

@Serializable
data class NebulaListen(
    val host: String = "::",
    val port: Int = 4242,
    val batch: Int = 64,
    /** 0 = system default */
    val readBuffer: Int = 0,
    val writeBuffer: Int = 0,
    val sendRecvError: String = "always",
    val acceptRecvError: String = "always",
    val udpOffloads: Boolean = false,
)

@Serializable
data class NebulaPunchy(
    val punch: Boolean = true,
    val respond: Boolean = true,
    val delay: String = "1s",
    val respondDelay: String = "5s",
)

@Serializable
data class NebulaSshUser(val user: String = "", val keys: List<String> = emptyList())

@Serializable
data class NebulaSshd(
    val isEnabled: Boolean = false,
    val listen: String = "127.0.0.1:2222",
    val hostKey: String = "",
    val authorizedUsers: List<NebulaSshUser> = emptyList(),
    val trustedCas: List<String> = emptyList(),
)

@Serializable
data class NebulaRelay(
    val relays: List<String> = emptyList(),
    val amRelay: Boolean = false,
    val useRelays: Boolean = true,
)

@Serializable
data class NebulaRouteMtu(val route: String = "", val mtu: Int = 0)

@Serializable
data class NebulaTun(
    val dropLocalBroadcast: Boolean = true,
    val dropMulticast: Boolean = true,
    val txQueue: Int = 500,
    val mtu: Int = 1300,
    val routes: List<NebulaRouteMtu> = emptyList(),
    val unsafeRoutes: List<UnsafeRoute> = emptyList(),
)

@Serializable
data class NebulaLogging(val format: String = "text", val disableTimestamp: Boolean = false)

@Serializable
data class NebulaStats(
    val type: String = "none",
    val prefix: String = "nebula",
    val protocol: String = "tcp",
    val host: String = "127.0.0.1:9999",
    val interval: String = "10s",
    val listen: String = "127.0.0.1:8080",
    val path: String = "/metrics",
    val namespace: String = "prometheusns",
    val subsystem: String = "nebula",
    val messageMetrics: Boolean = false,
    val lighthouseMetrics: Boolean = false,
)

@Serializable
data class NebulaHandshakes(
    val tryInterval: String = "100ms",
    val retries: Int = 20,
    val queryBuffer: Int = 64,
    val triggerBuffer: Int = 64,
)

@Serializable
data class NebulaTunnels(val dropInactive: Boolean = false, val inactivityTimeout: String = "10m")

@Serializable
data class NebulaConntrack(
    val tcpTimeout: String = "12m",
    val udpTimeout: String = "3m",
    val defaultTimeout: String = "10m",
)

@Serializable
data class NebulaFirewall(
    val outboundAction: String = "drop",
    val inboundAction: String = "drop",
    val defaultLocalCidrAny: Boolean = false,
    val conntrack: NebulaConntrack = NebulaConntrack(),
    /** user rules; the app's required rules are rendered on top of these */
    val inbound: List<FirewallRule> = emptyList(),
    val outbound: List<FirewallRule> = emptyList(),
)

/**
 * The advanced nebula settings, stored inside `profile.json`. Every field carries the default
 * from nebula's own `examples/config.yml`, so decoding an older or partial profile fills the
 * gaps: there is no separate normalisation step.
 */
@Serializable
data class NebulaAdvancedConfig(
    val pki: NebulaPki = NebulaPki(),
    val staticHosts: List<NebulaStaticHost> = emptyList(),
    val staticMap: NebulaStaticMap = NebulaStaticMap(),
    val lighthouse: NebulaLighthouse = NebulaLighthouse(),
    val listen: NebulaListen = NebulaListen(),
    val routines: Int = 1,
    val punchy: NebulaPunchy = NebulaPunchy(),
    val cipher: String = "aes",
    val preferredRanges: List<String> = emptyList(),
    val sshd: NebulaSshd = NebulaSshd(),
    val relay: NebulaRelay = NebulaRelay(),
    val tun: NebulaTun = NebulaTun(),
    val logging: NebulaLogging = NebulaLogging(),
    val stats: NebulaStats = NebulaStats(),
    val handshakes: NebulaHandshakes = NebulaHandshakes(),
    val tunnels: NebulaTunnels = NebulaTunnels(),
    val firewall: NebulaFirewall = NebulaFirewall(),
)

/** What the VPN service needs to bring the tunnel up. */
@Serializable
data class NebulaSite(
    /** `{name, id, rawConfig}` handed verbatim to mobile_nebula, which only injects `pki.key` */
    val configJson: String,
    val networks: List<String> = emptyList(),
    /** unsafe route CIDRs Android must send into the tun */
    val routes: List<String> = emptyList(),
    val mtu: Int,
)

/**
 * Editable, unparsed form of the config: the editor holds text for every typed field and a
 * boolean for every switch, so half-typed input survives a recomposition.
 */
data class NebulaDraft(
    val values: Map<String, String> = emptyMap(),
    val flags: Map<String, Boolean> = emptyMap(),
    val inbound: List<FirewallRule> = emptyList(),
    val outbound: List<FirewallRule> = emptyList(),
    val unsafeRoutes: List<UnsafeRoute> = emptyList(),
)

/** `errors` is keyed by field path, rules and routes by their id. */
data class DraftResult(
    val config: NebulaAdvancedConfig,
    val errors: Map<String, String> = emptyMap(),
)

/** How a draft field's text is parsed and validated. */
enum class FieldKind {
    TEXT,
    NUMBER,
    DURATION,
    HOST,
    HOSTPORT,
    LINES,
    IP_LINES,
    CIDR_LINES,
    HOSTPORT_LINES,
    FINGERPRINT_LINES,
    ALLOW_LINES,
    STATIC_HOST_LINES,
    CALCULATED_REMOTE_LINES,
    ROUTE_MTU_LINES,
    SSH_USER_LINES,
}

data class NebulaSelectOption(val key: String, val label: String)

/** The value a [ShowWhen] compares against: a switch is a flag, a select is a key. */
sealed interface ShowWhenValue {
    data class Flag(val isOn: Boolean) : ShowWhenValue

    data class Choice(val key: String) : ShowWhenValue
}

/** The field is shown only while `path` (a switch or a select) currently equals `equals`. */
data class ShowWhen(val path: String, val equals: ShowWhenValue)

/** One editable field of the advanced settings, as the generic editor renders it. */
data class NebulaFieldSpec(
    /** dotted path into the config, e.g. "punchy.delay"; the draft is keyed by it */
    val path: String,
    val label: String,
    val helper: String? = null,
    val placeholder: String? = null,
    val kind: FieldKind? = null,
    /** renders a SwitchRow and lives in [NebulaDraft.flags] rather than its values */
    val isSwitch: Boolean = false,
    /** renders a SelectRow */
    val options: List<NebulaSelectOption>? = null,
    val min: Int? = null,
    val max: Int? = null,
    val showWhen: ShowWhen? = null,
)

enum class SectionEditor { FIREWALL, UNSAFE_ROUTES }

data class NebulaSectionSpec(
    val key: String,
    val title: String,
    val subtitle: String,
    val footnote: String? = null,
    val fields: List<NebulaFieldSpec> = emptyList(),
    /** structured editor rendered after the fields */
    val editor: SectionEditor? = null,
)

data class RequiredFirewallRule(val proto: FirewallProto, val port: String, val reason: String)

data class RequiredRules(
    val inbound: List<RequiredFirewallRule> = emptyList(),
    val outbound: List<RequiredFirewallRule> = emptyList(),
)

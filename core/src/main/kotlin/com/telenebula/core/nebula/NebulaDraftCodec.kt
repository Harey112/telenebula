package com.telenebula.core.nebula

import com.telenebula.core.model.DraftResult
import com.telenebula.core.model.FirewallMatch
import com.telenebula.core.model.FirewallProto
import com.telenebula.core.model.FirewallRule
import com.telenebula.core.model.NebulaAdvancedConfig
import com.telenebula.core.model.NebulaDraft
import com.telenebula.core.model.RequiredFirewallRule
import com.telenebula.core.model.RequiredRules
import com.telenebula.core.model.UnsafeRoute

/** Config ↔ editor draft, and the validation messages the editor shows under a field. */
internal object NebulaDraftCodec {
    /** Text for every typed field, a boolean for every switch; rules and routes are carried as they are. */
    fun toDraft(config: NebulaAdvancedConfig): NebulaDraft {
        val values = LinkedHashMap<String, String>()
        val flags = LinkedHashMap<String, Boolean>()
        for (entry in NEBULA_FIELDS) {
            when (val binding = entry.binding) {
                is FieldBinding.Flag -> flags[entry.spec.path] = binding.read(config)
                is FieldBinding.Value -> values[entry.spec.path] = binding.read(config)
            }
        }
        return NebulaDraft(
            values = values,
            flags = flags,
            inbound = config.firewall.inbound,
            outbound = config.firewall.outbound,
            unsafeRoutes = config.tun.unsafeRoutes,
        )
    }

    /**
     * Parses every field over the defaults. A field that does not parse keeps its default and
     * records a message; rules and routes are carried into the config even when invalid, so the
     * editor can show what is wrong instead of dropping the user's work.
     */
    fun fromDraft(draft: NebulaDraft): DraftResult {
        var config = NebulaAdvancedConfig()
        val errors = LinkedHashMap<String, String>()
        for (entry in NEBULA_FIELDS) {
            val path = entry.spec.path
            when (val binding = entry.binding) {
                is FieldBinding.Flag -> {
                    // a switch the draft never recorded keeps its default: coercing it to false
                    // silently turned punching and relays off
                    config = binding.write(config, draft.flags[path] ?: binding.read(config))
                }
                is FieldBinding.Value -> when (val parsed = binding.write(config, draft.values[path].orEmpty())) {
                    is Parse.Ok -> config = parsed.value
                    is Parse.Invalid -> errors[path] = parsed.message
                }
            }
        }
        for (rule in draft.inbound + draft.outbound) {
            validateRule(rule)?.let { errors[rule.id] = it }
        }
        for (route in draft.unsafeRoutes) {
            validateUnsafeRoute(route)?.let { errors[route.id] = it }
        }
        return DraftResult(
            config = config.copy(
                firewall = config.firewall.copy(inbound = draft.inbound, outbound = draft.outbound),
                tun = config.tun.copy(unsafeRoutes = draft.unsafeRoutes),
            ),
            errors = errors,
        )
    }

    /** null when the rule is valid, else the message to show under it. */
    fun validateRule(rule: FirewallRule): String? {
        if (rule.proto != FirewallProto.ICMP && !NebulaText.isPortSpec(rule.port.trim())) {
            return "Port must be any, a number, a range like 200-901, or fragment"
        }
        val value = rule.value.trim()
        if (value.isEmpty()) return "Enter a ${rule.match.name.lowercase()}"
        if (rule.match == FirewallMatch.CIDR && value != "any" && !NebulaText.isCidr(value)) {
            return "Not a CIDR: $value"
        }
        val local = rule.localCidr.trim()
        if (local.isNotEmpty() && local != "any" && !NebulaText.isCidr(local)) {
            return "Local CIDR is not a CIDR: ${rule.localCidr}"
        }
        return null
    }

    fun validateUnsafeRoute(route: UnsafeRoute): String? {
        if (!NebulaText.isCidr(route.route)) return "Route is not a CIDR: ${orEmpty(route.route)}"
        if (!NebulaText.isIpAddress(route.via)) return "Via is not a nebula IP: ${orEmpty(route.via)}"
        if (route.mtu.trim().let { it.isNotEmpty() && !NebulaText.isDigits(it) }) return "MTU must be a number"
        if (route.metric.trim().let { it.isNotEmpty() && !NebulaText.isDigits(it) }) return "Metric must be a number"
        return null
    }

    /** The rules the app itself needs: always rendered, never editable. */
    fun requiredRules(msgPort: Int) = RequiredRules(
        inbound = listOf(
            RequiredFirewallRule(FirewallProto.ICMP, "any", "ping between nebula hosts"),
            RequiredFirewallRule(
                FirewallProto.TCP,
                msgPort.toString(),
                "messaging and call signaling (TCP on the message port)",
            ),
            RequiredFirewallRule(FirewallProto.UDP, "any", "WebRTC voice and video media (ephemeral UDP ports)"),
        ),
        outbound = listOf(RequiredFirewallRule(FirewallProto.ANY, "any", "everything this device sends")),
    )

    private fun orEmpty(value: String): String = value.ifEmpty { "(empty)" }
}

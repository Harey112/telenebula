package com.telenebula.core.nebula

import com.telenebula.core.model.FirewallMatch
import com.telenebula.core.model.FirewallProto
import com.telenebula.core.model.FirewallRule
import com.telenebula.core.model.NebulaAdvancedConfig
import com.telenebula.core.model.NebulaAllowRule
import com.telenebula.core.model.NebulaLogLevel
import com.telenebula.core.model.NebulaSite
import com.telenebula.core.model.Profile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Renders the nebula YAML-as-JSON this device runs with. The result is handed to mobile_nebula's
 * `RenderConfig` verbatim — it only injects `pki.key` — so this is a real wire boundary and the
 * one place in the core that builds JSON by hand: the keys are nebula's (snake_case), not ours,
 * and half of them are omitted rather than sent empty.
 */
internal object NebulaSiteRenderer {
    fun buildSite(profile: Profile, logLevel: NebulaLogLevel): NebulaSite {
        val config = profile.nebula
        val raw = render(profile, config, logLevel)
        val site = buildJsonObject {
            put("name", "telenebula")
            put("id", "telenebula-site")
            put("rawConfig", raw.toString())
        }
        return NebulaSite(
            configJson = site.toString(),
            networks = profile.networks,
            routes = config.tun.unsafeRoutes.filter { it.isInstalled }.map { it.route.trim() },
            mtu = config.tun.mtu,
        )
    }

    private fun render(profile: Profile, config: NebulaAdvancedConfig, logLevel: NebulaLogLevel): JsonObject {
        val hosts = profile.lighthouses.map { it.nebulaIp }
        val staticHostMap = LinkedHashMap<String, List<String>>()
        for (lighthouse in profile.lighthouses) {
            staticHostMap[lighthouse.nebulaIp] = listOf(lighthouse.underlay)
        }
        for (host in config.staticHosts) {
            staticHostMap[host.nebulaIp] = host.underlays
        }
        // phones behind carrier NAT cannot hole-punch each other, so with no explicit relay list the
        // lighthouses double as relays (they must run with am_relay on); an explicit list always wins
        val relays = if (config.relay.relays.isEmpty() && config.relay.useRelays) hosts else config.relay.relays

        return buildJsonObject {
            put("pki", buildJsonObject {
                put("ca", profile.caPem)
                put("cert", profile.certPem)
                put("blocklist", stringArray(config.pki.blocklist))
                put("disconnect_invalid", config.pki.disconnectInvalid)
                if (config.pki.initiatingVersion == 2) put("initiating_version", 2)
            })
            put("static_host_map", buildJsonObject {
                for ((ip, underlays) in staticHostMap) put(ip, stringArray(underlays))
            })
            put("static_map", buildJsonObject {
                put("cadence", config.staticMap.cadence)
                put("network", config.staticMap.network)
                put("lookup_timeout", config.staticMap.lookupTimeout)
            })
            put("lighthouse", buildJsonObject {
                put("am_lighthouse", false)
                put("interval", config.lighthouse.interval)
                put("hosts", stringArray(hosts))
                allowMap(config.lighthouse.remoteAllowList)?.let { put("remote_allow_list", it) }
                localAllowMap(config.lighthouse.localAllowList)?.let { put("local_allow_list", it) }
                if (config.lighthouse.advertiseAddrs.isNotEmpty()) {
                    put("advertise_addrs", stringArray(config.lighthouse.advertiseAddrs))
                }
                if (config.lighthouse.calculatedRemotes.isNotEmpty()) {
                    put("calculated_remotes", buildJsonObject {
                        for ((cidr, entries) in config.lighthouse.calculatedRemotes.groupBy { it.cidr }) {
                            put(cidr, buildJsonArray {
                                for (entry in entries) {
                                    add(buildJsonObject { put("mask", entry.mask); put("port", entry.port) })
                                }
                            })
                        }
                    })
                }
            })
            put("listen", buildJsonObject {
                put("host", config.listen.host)
                put("port", config.listen.port)
                put("batch", config.listen.batch)
                if (config.listen.readBuffer > 0) put("read_buffer", config.listen.readBuffer)
                if (config.listen.writeBuffer > 0) put("write_buffer", config.listen.writeBuffer)
                put("send_recv_error", config.listen.sendRecvError)
                put("accept_recv_error", config.listen.acceptRecvError)
                put("udp_offloads", config.listen.udpOffloads)
            })
            put("routines", config.routines)
            put("punchy", buildJsonObject {
                put("punch", config.punchy.punch)
                put("respond", config.punchy.respond)
                put("delay", config.punchy.delay)
                put("respond_delay", config.punchy.respondDelay)
            })
            put("cipher", config.cipher)
            if (config.preferredRanges.isNotEmpty()) put("preferred_ranges", stringArray(config.preferredRanges))
            if (config.sshd.isEnabled) {
                put("sshd", buildJsonObject {
                    put("enabled", true)
                    put("listen", config.sshd.listen)
                    put("host_key", config.sshd.hostKey)
                    put("authorized_users", buildJsonArray {
                        for (user in config.sshd.authorizedUsers) {
                            add(buildJsonObject { put("user", user.user); put("keys", stringArray(user.keys)) })
                        }
                    })
                    if (config.sshd.trustedCas.isNotEmpty()) put("trusted_cas", stringArray(config.sshd.trustedCas))
                })
            }
            put("relay", buildJsonObject {
                if (relays.isNotEmpty()) put("relays", stringArray(relays))
                put("am_relay", config.relay.amRelay)
                put("use_relays", config.relay.useRelays)
            })
            put("tun", buildJsonObject {
                put("disabled", false)
                put("drop_local_broadcast", config.tun.dropLocalBroadcast)
                put("drop_multicast", config.tun.dropMulticast)
                put("tx_queue", config.tun.txQueue)
                put("mtu", config.tun.mtu)
                if (config.tun.routes.isNotEmpty()) {
                    put("routes", buildJsonArray {
                        for (route in config.tun.routes) {
                            add(buildJsonObject { put("route", route.route); put("mtu", route.mtu) })
                        }
                    })
                }
                if (config.tun.unsafeRoutes.isNotEmpty()) {
                    put("unsafe_routes", buildJsonArray {
                        for (route in config.tun.unsafeRoutes) {
                            add(buildJsonObject {
                                put("route", route.route.trim())
                                put("via", route.via.trim())
                                number(route.mtu)?.let { put("mtu", it) }
                                number(route.metric)?.let { put("metric", it) }
                                put("install", route.isInstalled)
                            })
                        }
                    })
                }
            })
            put("logging", buildJsonObject {
                put("level", logLevel.name.lowercase())
                put("format", config.logging.format)
                put("disable_timestamp", config.logging.disableTimestamp)
            })
            when (config.stats.type) {
                "graphite" -> put("stats", buildJsonObject {
                    put("type", "graphite")
                    put("prefix", config.stats.prefix)
                    put("protocol", config.stats.protocol)
                    put("host", config.stats.host)
                    put("interval", config.stats.interval)
                    put("message_metrics", config.stats.messageMetrics)
                    put("lighthouse_metrics", config.stats.lighthouseMetrics)
                })
                "prometheus" -> put("stats", buildJsonObject {
                    put("type", "prometheus")
                    put("listen", config.stats.listen)
                    put("path", config.stats.path)
                    put("namespace", config.stats.namespace)
                    put("subsystem", config.stats.subsystem)
                    put("interval", config.stats.interval)
                    put("message_metrics", config.stats.messageMetrics)
                    put("lighthouse_metrics", config.stats.lighthouseMetrics)
                })
            }
            put("handshakes", buildJsonObject {
                put("try_interval", config.handshakes.tryInterval)
                put("retries", config.handshakes.retries)
                put("query_buffer", config.handshakes.queryBuffer)
                put("trigger_buffer", config.handshakes.triggerBuffer)
            })
            put("tunnels", buildJsonObject {
                put("drop_inactive", config.tunnels.dropInactive)
                put("inactivity_timeout", config.tunnels.inactivityTimeout)
            })
            put("firewall", buildJsonObject {
                put("outbound_action", config.firewall.outboundAction)
                put("inbound_action", config.firewall.inboundAction)
                put("default_local_cidr_any", config.firewall.defaultLocalCidrAny)
                put("conntrack", buildJsonObject {
                    put("tcp_timeout", config.firewall.conntrack.tcpTimeout)
                    put("udp_timeout", config.firewall.conntrack.udpTimeout)
                    put("default_timeout", config.firewall.conntrack.defaultTimeout)
                })
                put("outbound", buildJsonArray {
                    add(anyRule())
                    for (rule in config.firewall.outbound) add(rule.toNebulaRule())
                })
                put("inbound", buildJsonArray {
                    for (required in NebulaDraftCodec.requiredRules(profile.msgPort).inbound) {
                        add(buildJsonObject {
                            put("port", required.port)
                            put("proto", required.proto.wire)
                            put("host", "any")
                        })
                    }
                    for (rule in config.firewall.inbound) add(rule.toNebulaRule())
                })
            })
        }
    }

    private fun anyRule() = buildJsonObject {
        put("port", "any")
        put("proto", "any")
        put("host", "any")
    }

    private fun FirewallRule.toNebulaRule(): JsonObject = buildJsonObject {
        // an icmp rule has no ports to match on, whatever the editor holds
        put("port", if (proto == FirewallProto.ICMP) "any" else port.trim())
        put("proto", proto.wire)
        val trimmed = value.trim()
        if (match == FirewallMatch.GROUPS) {
            put("groups", stringArray(NebulaText.commaList(trimmed)))
        } else {
            put(match.name.lowercase(), trimmed)
        }
        localCidr.trim().ifEmpty { null }?.let { put("local_cidr", it) }
        caName.trim().ifEmpty { null }?.let { put("ca_name", it) }
        caSha.trim().ifEmpty { null }?.let { put("ca_sha", it) }
    }

    private val FirewallProto.wire: String get() = name.lowercase()

    private fun stringArray(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    private fun allowMap(rules: List<NebulaAllowRule>): JsonObject? {
        if (rules.isEmpty()) return null
        return JsonObject(rules.associate { it.target to JsonPrimitive(it.isAllowed) })
    }

    /** local_allow_list keeps interface patterns under `interfaces`, CIDRs at the top level. */
    private fun localAllowMap(rules: List<NebulaAllowRule>): JsonObject? {
        if (rules.isEmpty()) return null
        val top = LinkedHashMap<String, JsonElement>()
        val interfaces = LinkedHashMap<String, JsonElement>()
        for (rule in rules) {
            if (rule.target.contains('/')) {
                top[rule.target] = JsonPrimitive(rule.isAllowed)
            } else {
                interfaces[rule.target] = JsonPrimitive(rule.isAllowed)
            }
        }
        if (interfaces.isNotEmpty()) top["interfaces"] = JsonObject(interfaces)
        return JsonObject(top)
    }

    /** An optional numeric override: absent when the text is blank, `null` when it is not a number. */
    private fun number(text: String): JsonPrimitive? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val whole = trimmed.toLongOrNull()
        if (whole != null) return JsonPrimitive(whole)
        val decimal = trimmed.toDoubleOrNull()
        return if (decimal != null) JsonPrimitive(decimal) else JsonNull
    }
}

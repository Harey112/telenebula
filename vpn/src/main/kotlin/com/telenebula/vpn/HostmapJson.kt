package com.telenebula.vpn

import com.telenebula.vpn.model.HostmapEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * mobile_nebula's hostmap JSON follows nebula's control.HostInfo; field names have shifted
 * across versions (VpnIp vs VpnAddrs, Cert details nesting), so every read is defensive.
 * The document is parsed once and walked; nothing is re-serialised.
 */
internal object HostmapJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseList(raw: String, lighthouses: Set<String>): List<HostmapEntry> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        val out = ArrayList<HostmapEntry>(root.size)
        for (element in root) {
            val obj = element as? JsonObject ?: continue
            toEntry(obj, lighthouses)?.let(out::add)
        }
        return out
    }

    fun parseOne(raw: String, lighthouses: Set<String>): HostmapEntry? {
        val obj = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        return toEntry(obj, lighthouses)
    }

    private fun toEntry(raw: JsonObject, lighthouses: Set<String>): HostmapEntry? {
        val vpnIp = (raw.str("VpnIp") ?: raw.str("vpnIp") ?: raw.strList("VpnAddrs", "vpnAddrs").firstOrNull())
            ?.lowercase() ?: return null
        val cert = raw.obj("Cert") ?: raw.obj("cert")
        val details = cert?.obj("details") ?: cert?.obj("Details") ?: cert
        val relay = raw["RelayState"] ?: raw["relayState"] ?: raw["Relay"] ?: raw["relay"]
        val currentRemote = raw.str("CurrentRemote") ?: raw.str("currentRemote")
        return HostmapEntry(
            vpnIp = vpnIp,
            currentRemote = currentRemote,
            remoteAddrs = raw.strList("RemoteAddrs", "remoteAddrs"),
            certName = details?.str("name") ?: details?.str("Name"),
            certFingerprint = details?.str("fingerprint") ?: cert?.str("fingerprint") ?: cert?.str("Fingerprint"),
            isRelayed = currentRemote == null && relay != null && relay !is JsonNull,
            isLighthouse = vpnIp in lighthouses,
        )
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

    private fun JsonObject.strList(vararg keys: String): List<String> {
        val array = keys.firstNotNullOfOrNull { this[it] as? JsonArray } ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
    }
}

internal fun lighthouseSet(lighthouseIps: Collection<String>): Set<String> =
    if (lighthouseIps.isEmpty()) emptySet() else lighthouseIps.mapTo(HashSet(lighthouseIps.size)) { it.lowercase() }


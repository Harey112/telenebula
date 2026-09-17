package com.telenebula.core.nebula

import com.telenebula.core.CoreJson
import com.telenebula.core.model.FieldKind
import com.telenebula.core.model.FirewallRule
import com.telenebula.core.model.NebulaAdvancedConfig
import com.telenebula.core.model.NebulaDraft
import com.telenebula.core.model.NebulaLogLevel
import com.telenebula.core.model.NebulaSectionSpec
import com.telenebula.core.model.NebulaSite
import com.telenebula.core.model.Profile
import com.telenebula.core.model.SectionEditor
import com.telenebula.core.model.ShowWhenValue
import com.telenebula.core.model.UnsafeRoute
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden fixtures under `test/resources/nebula` were generated from the React Native build and
 * carried through every rewrite unchanged; they are the contract for what nebula receives. A change
 * to one of them is a deliberate change of the rendered config and needs its reason in the commit.
 */
class NebulaConfigTest {
    private val nebula = NebulaConfigRepository()

    // --- defaults ---

    @Test
    fun `defaults match the default nebula config`() {
        assertEquals(jsonFixture("defaults.json"), encode(NebulaAdvancedConfig()))
    }

    @Test
    fun `an empty stored config decodes to the defaults`() {
        assertEquals(NebulaAdvancedConfig(), decodeConfig("{}"))
    }

    /**
     * Was `normalize_legacy_partial_fills_only_what_is_missing`. Two deliberate differences now
     * that the config is typed rather than a JSON tree: a nested object is filled field by field
     * instead of replacing its default wholesale (a stored `conntrack` carrying only a TCP timeout
     * used to leave the other two blank, which the editor then rejected as invalid durations), and
     * a section the schema does not know is dropped rather than carried along.
     */
    @Test
    fun `a partial stored config keeps its values and fills the rest from the defaults`() {
        val config = decodeConfig(fixture("legacy_partial.json"))

        assertEquals(false, config.punchy.punch)
        assertEquals("3s", config.punchy.delay)
        assertEquals("5s", config.punchy.respondDelay)
        assertEquals(4, config.routines)
        assertEquals(1, config.staticHosts.size)
        assertEquals(1200, config.tun.mtu)
        assertEquals("reject", config.firewall.inboundAction)
        assertEquals("1m", config.firewall.conntrack.tcpTimeout)
        assertEquals("3m", config.firewall.conntrack.udpTimeout)
        assertEquals("10m", config.firewall.conntrack.defaultTimeout)
        assertEquals(jsonFixture("expected_normalized_legacy.json"), encode(config))
    }

    // --- site rendering ---

    @Test
    fun `site config matches the fixture for both log levels`() {
        for ((level, name) in listOf(NebulaLogLevel.INFO to "expected_site_info.json", NebulaLogLevel.DEBUG to "expected_site_debug.json")) {
            assertEquals("log level $level", expand(jsonFixture(name)), expand(nebula.buildSite(profile(), level)))
        }
    }

    @Test
    fun `site config json has the site envelope shape`() {
        val site = nebula.buildSite(profile(), NebulaLogLevel.INFO)
        val envelope = Json.parseToJsonElement(site.configJson).jsonObject
        assertEquals("telenebula", envelope.getValue("name").jsonPrimitive.content)
        assertEquals("telenebula-site", envelope.getValue("id").jsonPrimitive.content)
        assertTrue("rawConfig travels as a string", envelope.getValue("rawConfig").jsonPrimitive.isString)
        assertEquals(listOf("192.168.100.0/24"), site.routes)
        assertEquals(1400, site.mtu)
    }

    @Test
    fun `site config fills a missing nebula section with defaults`() {
        val site = nebula.buildSite(profile().copy(nebula = NebulaAdvancedConfig()), NebulaLogLevel.INFO)
        val raw = rawConfig(site)
        assertEquals(1300, raw.getValue("tun").jsonObject.getValue("mtu").jsonPrimitive.int)
        assertEquals("1s", raw.getValue("punchy").jsonObject.getValue("delay").jsonPrimitive.content)
        assertEquals(3, raw.getValue("firewall").jsonObject.getValue("inbound").jsonArray.size)
        assertEquals(emptyList<String>(), site.routes)
        // no relay list configured: the lighthouses relay
        assertEquals(
            listOf("fd00:1234:5678::1", "fd00:1234:5678::a"),
            raw.getValue("relay").jsonObject.getValue("relays").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `site config relays through the lighthouses when none are listed`() {
        val base = profile()
        val lighthouses = listOf("fd00:1234:5678::1", "fd00:1234:5678::a")

        val viaLighthouse = rawConfig(
            nebula.buildSite(
                base.copy(nebula = base.nebula.copy(relay = base.nebula.relay.copy(relays = emptyList(), amRelay = false, useRelays = true))),
                NebulaLogLevel.INFO,
            ),
        ).getValue("relay").jsonObject
        assertEquals(lighthouses, viaLighthouse.getValue("relays").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(false, viaLighthouse.getValue("am_relay").jsonPrimitive.boolean)
        assertEquals(true, viaLighthouse.getValue("use_relays").jsonPrimitive.boolean)

        // relays switched off: nothing is listed
        val off = rawConfig(
            nebula.buildSite(
                base.copy(nebula = base.nebula.copy(relay = base.nebula.relay.copy(relays = emptyList(), useRelays = false))),
                NebulaLogLevel.INFO,
            ),
        ).getValue("relay").jsonObject
        assertEquals(setOf("am_relay", "use_relays"), off.keys)

        // an explicit list always wins over the lighthouses
        val explicit = rawConfig(
            nebula.buildSite(
                base.copy(nebula = base.nebula.copy(relay = base.nebula.relay.copy(relays = listOf("fd00:1234:5678::7"), useRelays = true))),
                NebulaLogLevel.INFO,
            ),
        ).getValue("relay").jsonObject
        assertEquals(listOf("fd00:1234:5678::7"), explicit.getValue("relays").jsonArray.map { it.jsonPrimitive.content })
    }

    // --- draft ---

    @Test
    fun `draft of the defaults matches the fixture`() {
        val draft = nebula.draftFrom(NebulaAdvancedConfig())
        val expected = jsonFixture("expected_draft.json")
        assertEquals(stringMap(expected, "values"), draft.values)
        assertEquals(boolMap(expected, "flags"), draft.flags)
        assertEquals(emptyList<FirewallRule>(), draft.inbound)
        assertEquals(emptyList<FirewallRule>(), draft.outbound)
        assertEquals(emptyList<UnsafeRoute>(), draft.unsafeRoutes)
    }

    @Test
    fun `the defaults round trip through the draft without errors`() {
        val result = nebula.configFrom(nebula.draftFrom(NebulaAdvancedConfig()))
        assertEquals(emptyMap<String, String>(), result.errors)
        assertEquals(NebulaAdvancedConfig(), result.config)
        assertEquals(jsonFixture("expected_roundtrip.json").getValue("config").jsonObject, encode(result.config))
    }

    @Test
    fun `a stored profile config round trips through the draft`() {
        val config = profile().nebula
        val draft = nebula.draftFrom(config)
        val expected = jsonFixture("expected_profile_draft.json")
        assertEquals(stringMap(expected, "values"), draft.values)
        assertEquals(boolMap(expected, "flags"), draft.flags)

        val result = nebula.configFrom(draft)
        assertEquals(emptyMap<String, String>(), result.errors)
        assertEquals(config, result.config)
    }

    @Test
    fun `a draft without flags keeps the switch defaults`() {
        val kept = nebula.configFrom(NebulaDraft()).config
        assertTrue(kept.punchy.punch)
        assertTrue(kept.punchy.respond)
        assertTrue(kept.relay.useRelays)
        assertTrue(!kept.relay.amRelay)
        assertTrue(kept.tun.dropMulticast)

        // an explicit false still wins
        val off = nebula.configFrom(NebulaDraft(flags = mapOf("punchy.punch" to false))).config
        assertTrue(!off.punchy.punch)
        assertTrue(off.punchy.respond)
    }

    @Test
    fun `select fields fall back to their first option`() {
        val draft = nebula.draftFrom(NebulaAdvancedConfig())
        val edited = draft.copy(
            values = draft.values.toMutableMap().apply {
                put("cipher", "rot13")
                put("pki.initiatingVersion", "2")
                remove("stats.type")
            },
        )
        val result = nebula.configFrom(edited)
        assertEquals(emptyMap<String, String>(), result.errors)
        assertEquals("aes", result.config.cipher)
        assertEquals(2, result.config.pki.initiatingVersion)
        assertEquals("none", result.config.stats.type)
    }

    @Test
    fun `every field kind parses well formed input`() {
        val base = nebula.draftFrom(NebulaAdvancedConfig())
        val edited = base.copy(
            values = base.values + mapOf(
                "pki.blocklist" to "C99D4E650533B92061B09918E838A5A0A6AAEE21EED1D12FD937682865936C72",
                "staticHosts" to " fd00::9 = 203.0.113.9:4242 , [fd00::1]:4242 ",
                "lighthouse.remoteAllowList" to "172.16.0.0/12 = false\ntun0 = true",
                "lighthouse.advertiseAddrs" to "1.2.3.4:0\r\nrelay.example.com:65535",
                "lighthouse.calculatedRemotes" to "10.0.10.0/24, 192.168.1.0/24, 4242",
                "listen.host" to "relay-1.example.com",
                "listen.port" to "0",
                "punchy.delay" to "250ms",
                "preferredRanges" to "fd00::/8\n10.0.0.0/8",
                "tun.routes" to "fd00:1234:5678::/64, 8800",
                "sshd.authorizedUsers" to "alice: ssh-ed25519 AAAA, ssh-rsa BBBB",
                "sshd.trustedCas" to "ca-one\n\nca-two",
            ),
        )
        val result = nebula.configFrom(edited)
        assertEquals(emptyMap<String, String>(), result.errors)
        val config = result.config
        assertEquals(listOf("fd00::9"), config.staticHosts.map { it.nebulaIp })
        assertEquals(listOf("203.0.113.9:4242", "[fd00::1]:4242"), config.staticHosts.single().underlays)
        assertEquals(
            listOf("172.16.0.0/12" to false, "tun0" to true),
            config.lighthouse.remoteAllowList.map { it.target to it.isAllowed },
        )
        assertEquals(listOf("1.2.3.4:0", "relay.example.com:65535"), config.lighthouse.advertiseAddrs)
        assertEquals(
            listOf(Triple("10.0.10.0/24", "192.168.1.0/24", 4242)),
            config.lighthouse.calculatedRemotes.map { Triple(it.cidr, it.mask, it.port) },
        )
        assertEquals(0, config.listen.port)
        assertEquals(listOf("fd00:1234:5678::/64" to 8800), config.tun.routes.map { it.route to it.mtu })
        assertEquals(listOf("ssh-ed25519 AAAA", "ssh-rsa BBBB"), config.sshd.authorizedUsers.single().keys)
        assertEquals(listOf("ca-one", "ca-two"), config.sshd.trustedCas)
    }

    // --- validation ---

    @Test
    fun `invalid draft values yield the fixture's messages`() {
        val expected = jsonFixture("expected_validation.json")
        val draftJson = expected.getValue("invalidDraft").jsonObject
        val draft = NebulaDraft(
            values = stringMap(draftJson, "values"),
            flags = boolMap(draftJson, "flags"),
            inbound = rules(draftJson, "inbound"),
            outbound = rules(draftJson, "outbound"),
            unsafeRoutes = CoreJson.decodeFromJsonElement(ROUTES, draftJson.getValue("unsafeRoutes")),
        )

        val result = nebula.configFrom(draft)
        assertEquals(stringMap(expected, "draftErrors"), result.errors)
        // a field that failed to parse keeps its default
        assertEquals(4242, result.config.listen.port)
        // rules and routes are carried into the config even when invalid
        assertEquals(draft.inbound, result.config.firewall.inbound)
        assertEquals(draft.unsafeRoutes, result.config.tun.unsafeRoutes)
    }

    @Test
    fun `firewall rule validation matches the fixture`() {
        for (case in jsonFixture("expected_validation.json").getValue("rules").jsonArray) {
            val rule = CoreJson.decodeFromJsonElement(FirewallRule.serializer(), case.jsonObject.getValue("rule"))
            assertEquals("rule ${rule.id}", errorOf(case), nebula.validateRule(rule))
        }
    }

    @Test
    fun `unsafe route validation matches the fixture`() {
        for (case in jsonFixture("expected_validation.json").getValue("routes").jsonArray) {
            val route = CoreJson.decodeFromJsonElement(UnsafeRoute.serializer(), case.jsonObject.getValue("route"))
            assertEquals("route ${route.id}", errorOf(case), nebula.validateUnsafeRoute(route))
        }
    }

    // --- tables ---

    @Test
    fun `required rules resolve the message port`() {
        assertEquals(jsonFixture("expected_required_rules.json"), requiredRulesJson(4433))
        assertEquals("65535", nebula.requiredRules(65535).inbound[1].port)
    }

    @Test
    fun `sections match the fixture table`() {
        val expected = Json.decodeFromString(ListSerializer(SectionJson.serializer()), fixture("expected_sections.json"))
        assertEquals(expected, nebula.sections().map { it.toJsonModel() })
    }

    @Test
    fun `every field of the table reads and writes its own config value`() {
        // a lens pointing at the wrong field would make the editor lose an edit silently
        val draft = nebula.draftFrom(profile().nebula)
        assertEquals(profile().nebula, nebula.configFrom(draft).config)
        assertEquals(NEBULA_FIELDS.map { it.spec.path }.distinct().size, NEBULA_FIELDS.size)
    }

    // --- helpers ---

    private fun profile(): Profile = CoreJson.decodeFromString(Profile.serializer(), fixture("profile.json"))

    private fun rawConfig(site: NebulaSite): JsonObject =
        Json.parseToJsonElement(
            Json.parseToJsonElement(site.configJson).jsonObject.getValue("rawConfig").jsonPrimitive.content,
        ).jsonObject

    /** The site with its two nested JSON strings expanded, so the comparison is structural. */
    private fun expand(site: NebulaSite): JsonObject = buildJsonObject {
        put("configJson", expandEnvelope(site.configJson))
        put("networks", Golden.encodeToJsonElement(site.networks))
        put("routes", Golden.encodeToJsonElement(site.routes))
        put("mtu", JsonPrimitive(site.mtu))
    }

    private fun expand(fixture: JsonObject): JsonObject = buildJsonObject {
        put("configJson", expandEnvelope(fixture.getValue("configJson").jsonPrimitive.content))
        put("networks", fixture.getValue("networks"))
        put("routes", fixture.getValue("routes"))
        put("mtu", fixture.getValue("mtu"))
    }

    private fun expandEnvelope(configJson: String): JsonObject {
        val envelope = Json.parseToJsonElement(configJson).jsonObject
        return buildJsonObject {
            for ((key, value) in envelope) {
                if (key == "rawConfig") {
                    put(key, Json.parseToJsonElement(value.jsonPrimitive.content))
                } else {
                    put(key, value)
                }
            }
        }
    }

    private fun requiredRulesJson(msgPort: Int): JsonObject {
        val rules = nebula.requiredRules(msgPort)
        fun render(list: List<com.telenebula.core.model.RequiredFirewallRule>) =
            Golden.encodeToJsonElement(list.map { RuleJson(it.proto.name.lowercase(), it.port, it.reason) })
        return buildJsonObject {
            put("inbound", render(rules.inbound))
            put("outbound", render(rules.outbound))
        }
    }

    private fun errorOf(case: kotlinx.serialization.json.JsonElement): String? =
        case.jsonObject.getValue("error").jsonPrimitive.let { if (it is kotlinx.serialization.json.JsonNull) null else it.content }

    private fun rules(source: JsonObject, key: String): List<FirewallRule> =
        CoreJson.decodeFromJsonElement(RULES, source.getValue(key))

    private fun stringMap(source: JsonObject, key: String): Map<String, String> =
        source.getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun boolMap(source: JsonObject, key: String): Map<String, Boolean> =
        source.getValue(key).jsonObject.mapValues { it.value.jsonPrimitive.boolean }

    private fun encode(config: NebulaAdvancedConfig): JsonObject =
        Golden.encodeToJsonElement(NebulaAdvancedConfig.serializer(), config).jsonObject

    private fun decodeConfig(text: String): NebulaAdvancedConfig =
        CoreJson.decodeFromString(NebulaAdvancedConfig.serializer(), text)

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/nebula/$name")) { "missing fixture $name" }
            .use { String(it.readBytes(), Charsets.UTF_8) }

    private fun jsonFixture(name: String): JsonObject = Json.parseToJsonElement(fixture(name)).jsonObject

    private fun NebulaSectionSpec.toJsonModel() = SectionJson(
        key = key,
        title = title,
        subtitle = subtitle,
        footnote = footnote,
        fields = fields.map { field ->
            FieldJson(
                path = field.path,
                label = field.label,
                helper = field.helper,
                placeholder = field.placeholder,
                kind = field.kind?.wire,
                isSwitch = field.isSwitch,
                options = field.options?.map { OptionJson(it.key, it.label) },
                min = field.min,
                max = field.max,
                showWhen = field.showWhen?.let { rule ->
                    ShowWhenJson(
                        rule.path,
                        when (val expected = rule.equals) {
                            is ShowWhenValue.Flag -> JsonPrimitive(expected.isOn)
                            is ShowWhenValue.Choice -> JsonPrimitive(expected.key)
                        },
                    )
                },
            )
        },
        editor = editor?.wire,
    )

    private val FieldKind.wire: String get() = name.lowercase().replace('_', '-')

    private val SectionEditor.wire: String get() = name.lowercase().replace('_', '-')

    private companion object {
        val Golden = Json { encodeDefaults = true; explicitNulls = false }
        val RULES = ListSerializer(FirewallRule.serializer())
        val ROUTES = ListSerializer(UnsafeRoute.serializer())
    }

    @Serializable
    private data class RuleJson(val proto: String, val port: String, val reason: String)

    @Serializable
    private data class OptionJson(val key: String, val label: String)

    @Serializable
    private data class ShowWhenJson(val path: String, val equals: JsonPrimitive)

    @Serializable
    private data class FieldJson(
        val path: String,
        val label: String,
        val helper: String? = null,
        val placeholder: String? = null,
        val kind: String? = null,
        val isSwitch: Boolean = false,
        val options: List<OptionJson>? = null,
        val min: Int? = null,
        val max: Int? = null,
        val showWhen: ShowWhenJson? = null,
    )

    @Serializable
    private data class SectionJson(
        val key: String,
        val title: String,
        val subtitle: String,
        val footnote: String? = null,
        val fields: List<FieldJson> = emptyList(),
        val editor: String? = null,
    )
}

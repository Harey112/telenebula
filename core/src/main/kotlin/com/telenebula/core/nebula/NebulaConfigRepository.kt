package com.telenebula.core.nebula

import com.telenebula.core.model.DraftResult
import com.telenebula.core.model.FirewallRule
import com.telenebula.core.model.NebulaAdvancedConfig
import com.telenebula.core.model.NebulaDraft
import com.telenebula.core.model.NebulaFieldSpec
import com.telenebula.core.model.NebulaLogLevel
import com.telenebula.core.model.NebulaSectionSpec
import com.telenebula.core.model.NebulaSite
import com.telenebula.core.model.Profile
import com.telenebula.core.model.RequiredRules
import com.telenebula.core.model.ShowWhenValue
import com.telenebula.core.model.UnsafeRoute

/**
 * The nebula configuration rules in one place: the editor's field table, draft ↔ config, the
 * validation messages, and the site config the VPN service runs with. Everything here is a pure
 * function over immutable data — no I/O, no state — so callers need no dispatcher.
 */
class NebulaConfigRepository {
    /** The section/field table driving the advanced editor. */
    fun sections(): List<NebulaSectionSpec> = NEBULA_SECTIONS

    fun defaults(): NebulaAdvancedConfig = NebulaAdvancedConfig()

    fun buildSite(profile: Profile, logLevel: NebulaLogLevel): NebulaSite =
        NebulaSiteRenderer.buildSite(profile, logLevel)

    fun draftFrom(config: NebulaAdvancedConfig): NebulaDraft = NebulaDraftCodec.toDraft(config)

    /** Parses every draft field; `errors` is keyed by field path (rules and routes by their id). */
    fun configFrom(draft: NebulaDraft): DraftResult = NebulaDraftCodec.fromDraft(draft)

    /** null when valid, else the message to show under the rule. */
    fun validateRule(rule: FirewallRule): String? = NebulaDraftCodec.validateRule(rule)

    fun validateUnsafeRoute(route: UnsafeRoute): String? = NebulaDraftCodec.validateUnsafeRoute(route)

    /** The app's locked firewall rules with the message port filled in. */
    fun requiredRules(msgPort: Int): RequiredRules = NebulaDraftCodec.requiredRules(msgPort)

    companion object {
        /** A field with `showWhen` is shown only while the switch or select it names has that value. */
        fun isFieldVisible(spec: NebulaFieldSpec, draft: NebulaDraft): Boolean {
            val rule = spec.showWhen ?: return true
            return when (val expected = rule.equals) {
                is ShowWhenValue.Flag -> draft.flags[rule.path] == expected.isOn
                is ShowWhenValue.Choice -> draft.values[rule.path] == expected.key
            }
        }
    }
}

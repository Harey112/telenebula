package com.telenebula.app.nebula

import com.telenebula.core.model.DraftResult
import com.telenebula.core.model.FirewallProto
import com.telenebula.core.model.FirewallRule
import com.telenebula.core.model.NebulaAdvancedConfig
import com.telenebula.core.model.NebulaDraft
import com.telenebula.core.model.NebulaSectionSpec
import com.telenebula.core.model.RequiredRules
import com.telenebula.core.model.UnsafeRoute
import com.telenebula.core.nebula.NebulaConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class NebulaDraftState(
    val draft: NebulaDraft = NebulaDraft(),
    /** field path or rule/route id → message, set by the last commit */
    val errors: Map<String, String> = emptyMap(),
    val isOpen: Boolean = false,
    val openSections: Set<String> = emptySet(),
    /** the editor's section/field table; empty until [NebulaDraftStore.hydrateFrom] ran */
    val sections: List<NebulaSectionSpec> = emptyList(),
    /** the app's locked firewall rules, message port already filled in */
    val required: RequiredRules = RequiredRules(),
)

enum class RuleDirection { INBOUND, OUTBOUND }

/**
 * Draft of the advanced nebula settings, shared by the setup and lighthouse screens. The core owns
 * parsing and validation; this store only holds the unparsed text and the last commit's errors.
 */
class NebulaDraftStore(private val nebulaConfig: NebulaConfigRepository) {
    private val mutable = MutableStateFlow(NebulaDraftState())
    val state: StateFlow<NebulaDraftState> = mutable.asStateFlow()

    /** Loads a stored config into the draft along with the editor tables. */
    fun hydrateFrom(stored: NebulaAdvancedConfig, msgPort: Int) {
        val draft = nebulaConfig.draftFrom(stored)
        val sections = nebulaConfig.sections()
        val required = nebulaConfig.requiredRules(msgPort)
        mutable.update { it.copy(draft = draft, errors = emptyMap(), sections = sections, required = required) }
    }

    /** Parses the draft; records the errors and opens the fold when there are any. */
    fun commit(): DraftResult {
        val result = nebulaConfig.configFrom(state.value.draft)
        mutable.update { it.copy(errors = result.errors, isOpen = it.isOpen || result.errors.isNotEmpty()) }
        return result
    }

    fun resetToDefaults() {
        val draft = nebulaConfig.draftFrom(nebulaConfig.defaults())
        mutable.update { it.copy(draft = draft, errors = emptyMap(), isOpen = true) }
    }

    fun setValue(path: String, value: String) = mutable.update { s ->
        s.copy(draft = s.draft.copy(values = s.draft.values + (path to value)))
    }

    fun setFlag(path: String, value: Boolean) = mutable.update { s ->
        s.copy(draft = s.draft.copy(flags = s.draft.flags + (path to value)))
    }

    fun addRule(direction: RuleDirection) =
        setRules(direction) { it + FirewallRule(id = UUID.randomUUID().toString(), proto = FirewallProto.TCP) }

    fun updateRule(direction: RuleDirection, rule: FirewallRule) =
        setRules(direction) { list -> list.map { if (it.id == rule.id) rule else it } }

    fun removeRule(direction: RuleDirection, id: String) = setRules(direction) { list -> list.filter { it.id != id } }

    fun addUnsafeRoute() = setUnsafeRoutes { it + UnsafeRoute(id = UUID.randomUUID().toString()) }

    fun updateUnsafeRoute(route: UnsafeRoute) = setUnsafeRoutes { list -> list.map { if (it.id == route.id) route else it } }

    fun removeUnsafeRoute(id: String) = setUnsafeRoutes { list -> list.filter { it.id != id } }

    fun toggleOpen() = mutable.update { it.copy(isOpen = !it.isOpen) }

    fun toggleSection(key: String) = mutable.update { s ->
        s.copy(openSections = if (key in s.openSections) s.openSections - key else s.openSections + key)
    }

    private fun setRules(direction: RuleDirection, transform: (List<FirewallRule>) -> List<FirewallRule>) = mutable.update { s ->
        val draft = when (direction) {
            RuleDirection.INBOUND -> s.draft.copy(inbound = transform(s.draft.inbound))
            RuleDirection.OUTBOUND -> s.draft.copy(outbound = transform(s.draft.outbound))
        }
        s.copy(draft = draft)
    }

    private fun setUnsafeRoutes(transform: (List<UnsafeRoute>) -> List<UnsafeRoute>) = mutable.update { s ->
        s.copy(draft = s.draft.copy(unsafeRoutes = transform(s.draft.unsafeRoutes)))
    }
}

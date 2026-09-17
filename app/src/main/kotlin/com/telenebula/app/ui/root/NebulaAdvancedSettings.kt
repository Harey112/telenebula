package com.telenebula.app.ui.root

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.LocalAppGraph
import com.telenebula.app.nebula.NebulaDraftState
import com.telenebula.app.nebula.NebulaDraftStore
import com.telenebula.app.nebula.RuleDirection
import com.telenebula.app.ui.fragments.ButtonVariant
import com.telenebula.app.ui.fragments.Collapsible
import com.telenebula.app.ui.fragments.ConfigFieldList
import com.telenebula.app.ui.fragments.FirewallRuleEditor
import com.telenebula.app.ui.fragments.PrimaryButton
import com.telenebula.app.ui.fragments.UnsafeRouteEditor
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType
import com.telenebula.core.model.NebulaSectionSpec
import com.telenebula.core.model.SectionEditor

/**
 * Every nebula option the official example config exposes, folded behind one chevron and grouped
 * per config section. Shared by the setup and lighthouse screens through the app-wide draft store.
 */
@Composable
fun NebulaAdvancedSettings() {
    val store = LocalAppGraph.current.nebulaDraft
    val state by store.state.collectAsStateWithLifecycle()
    Collapsible(
        title = "Advanced nebula settings",
        subtitle = "Every option of nebula's config.yml, with safe defaults",
        isOpen = state.isOpen,
        onToggle = store::toggleOpen,
        hasError = state.errors.isNotEmpty(),
    ) {
        Text(
            "Defaults follow the official nebula example. Change these only when your network needs it; a wrong value keeps the tunnel from starting and shows as an error.",
            style = TnType.small,
            color = TnTheme.colors.textMuted,
            modifier = Modifier.padding(bottom = TnSpace.md),
        )
        for (section in state.sections) {
            key(section.key) { NebulaSection(section, state, store) }
        }
        Spacer(modifier = Modifier.height(TnSpace.lg))
        PrimaryButton("Reset to defaults", store::resetToDefaults, variant = ButtonVariant.SECONDARY)
    }
}

@Composable
private fun NebulaSection(section: NebulaSectionSpec, state: NebulaDraftState, store: NebulaDraftStore) {
    val draft = state.draft
    val errors = state.errors
    val hasError = errors.isNotEmpty() && (
        section.fields.any { errors.containsKey(it.path) } ||
            (section.editor == SectionEditor.FIREWALL && (draft.inbound.any { errors.containsKey(it.id) } || draft.outbound.any { errors.containsKey(it.id) })) ||
            (section.editor == SectionEditor.UNSAFE_ROUTES && draft.unsafeRoutes.any { errors.containsKey(it.id) })
        )
    Collapsible(
        title = section.title,
        subtitle = section.subtitle,
        isOpen = section.key in state.openSections,
        onToggle = { store.toggleSection(section.key) },
        hasError = hasError,
        isNested = true,
    ) {
        ConfigFieldList(section.fields, draft, errors, section.footnote, store::setValue, store::setFlag)
        when (section.editor) {
            SectionEditor.FIREWALL -> {
                FirewallRuleEditor(
                    title = "Inbound",
                    required = state.required.inbound,
                    rules = draft.inbound,
                    errors = errors,
                    onAdd = { store.addRule(RuleDirection.INBOUND) },
                    onUpdate = { store.updateRule(RuleDirection.INBOUND, it) },
                    onRemove = { store.removeRule(RuleDirection.INBOUND, it) },
                )
                FirewallRuleEditor(
                    title = "Outbound",
                    required = state.required.outbound,
                    rules = draft.outbound,
                    errors = errors,
                    onAdd = { store.addRule(RuleDirection.OUTBOUND) },
                    onUpdate = { store.updateRule(RuleDirection.OUTBOUND, it) },
                    onRemove = { store.removeRule(RuleDirection.OUTBOUND, it) },
                )
            }
            SectionEditor.UNSAFE_ROUTES -> UnsafeRouteEditor(draft.unsafeRoutes, errors, store::addUnsafeRoute, store::updateUnsafeRoute, store::removeUnsafeRoute)
            null -> Unit
        }
    }
}

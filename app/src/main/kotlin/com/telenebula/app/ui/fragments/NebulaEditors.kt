package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType
import com.telenebula.core.model.FieldKind
import com.telenebula.core.model.FirewallMatch
import com.telenebula.core.model.FirewallProto
import com.telenebula.core.model.FirewallRule
import com.telenebula.core.model.NebulaDraft
import com.telenebula.core.model.NebulaFieldSpec
import com.telenebula.core.model.RequiredFirewallRule
import com.telenebula.core.model.UnsafeRoute
import com.telenebula.core.nebula.NebulaConfigRepository

private val PROTO_OPTIONS = FirewallProto.entries.map { SelectOption(it.key, if (it == FirewallProto.ANY) "Any" else it.name) }
private val MATCH_OPTIONS = listOf(
    SelectOption(FirewallMatch.HOST.key, "Host"),
    SelectOption(FirewallMatch.GROUP.key, "Group"),
    SelectOption(FirewallMatch.GROUPS.key, "Groups"),
    SelectOption(FirewallMatch.CIDR.key, "CIDR"),
)

private val FirewallProto.key: String get() = name.lowercase()
private val FirewallMatch.key: String get() = name.lowercase()

/** Setting rows carry their own side padding; inside a folded card they bleed back to its edge. */
private fun Modifier.bleed(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = (amount * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = constraints.minWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** Renders a section's fields from their specs: switches, selects and monospace text drafts. */
@Composable
fun ConfigFieldList(
    fields: List<NebulaFieldSpec>,
    draft: NebulaDraft,
    errors: Map<String, String>,
    footnote: String?,
    onValue: (String, String) -> Unit,
    onFlag: (String, Boolean) -> Unit,
) {
    val colors = TnTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        for (spec in fields) {
            if (!NebulaConfigRepository.isFieldVisible(spec, draft)) continue
            key(spec.path) {
                val specOptions = spec.options
                when {
                    spec.isSwitch -> {
                        val checked = draft.flags[spec.path] == true
                        SwitchRowBleed(spec.label, spec.helper, checked) { onFlag(spec.path, !checked) }
                    }
                    specOptions != null -> {
                        val options = remember(specOptions) { specOptions.map { SelectOption(it.key, it.label) } }
                        Column(modifier = Modifier.bleed(TnSpace.lg)) {
                            SelectMenuRow(spec.label, options, draft.values[spec.path] ?: options.firstOrNull()?.key ?: "", { onValue(spec.path, it) })
                        }
                    }
                    else -> TnTextField(
                        value = draft.values[spec.path] ?: "",
                        onChange = { onValue(spec.path, it) },
                        label = spec.label,
                        placeholder = spec.placeholder,
                        helper = spec.helper,
                        error = errors[spec.path],
                        isMono = true,
                        isMultiline = spec.kind.isLines,
                        isNumeric = spec.kind == FieldKind.NUMBER,
                        hasAutoCapitalize = false,
                    )
                }
            }
        }
        if (footnote != null) Text(footnote, style = TnType.caption, color = colors.textMuted, modifier = Modifier.padding(top = TnSpace.md))
    }
}

private val FieldKind?.isLines: Boolean
    get() = when (this) {
        null, FieldKind.TEXT, FieldKind.NUMBER, FieldKind.DURATION, FieldKind.HOST, FieldKind.HOSTPORT -> false
        else -> true
    }

@Composable
private fun SwitchRowBleed(title: String, subtitle: String?, checked: Boolean, onToggle: () -> Unit) {
    Column(modifier = Modifier.bleed(TnSpace.lg)) {
        SwitchRow(TnIcon.SETTINGS, title, checked, onToggle, subtitle = subtitle)
    }
}

@Composable
private fun EditorTitle(text: String) {
    Text(
        text.uppercase(),
        style = TnType.small.copy(letterSpacing = 0.6.sp),
        color = TnTheme.colors.textMuted,
        modifier = Modifier.padding(top = TnSpace.lg, bottom = TnSpace.sm),
    )
}

@Composable
private fun EditorCard(title: String, removeLabel: String, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val colors = TnTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = TnSpace.sm)
            .border(Dp.Hairline, colors.hairline, RoundedCornerShape(TnRadius.md))
            .padding(start = TnSpace.md, end = TnSpace.md, bottom = TnSpace.md, top = TnSpace.sm),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = TnType.body.copy(fontWeight = FontWeight.Medium), color = colors.text, modifier = Modifier.weight(1f))
            Icon(
                TnIcon.TRASH,
                tint = colors.danger,
                size = 18.dp,
                modifier = Modifier.clickable(role = Role.Button, onClick = onRemove).semantics { contentDescription = removeLabel }.padding(TnSpace.sm),
            )
        }
        content()
    }
}

/** Locked app rules first, then the user's editable rules for one direction. */
@Composable
fun FirewallRuleEditor(
    title: String,
    required: List<RequiredFirewallRule>,
    rules: List<FirewallRule>,
    errors: Map<String, String>,
    onAdd: () -> Unit,
    onUpdate: (FirewallRule) -> Unit,
    onRemove: (String) -> Unit,
) {
    val colors = TnTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        EditorTitle(title)
        for (rule in required) {
            key(rule.proto, rule.port) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = TnSpace.sm)
                        .clip(RoundedCornerShape(TnRadius.md))
                        .background(colors.surfaceRaised)
                        .padding(horizontal = TnSpace.md, vertical = TnSpace.sm),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TnSpace.sm)) {
                        Icon(TnIcon.LOCK, tint = colors.textMuted, size = 14.dp)
                        Text("${rule.proto.name} ${rule.port} from any host", style = TnType.small.copy(fontFamily = FontFamily.Monospace), color = colors.text)
                    }
                    Text(rule.reason, style = TnType.caption, color = colors.textMuted, maxLines = 2)
                }
            }
        }
        rules.forEachIndexed { index, rule ->
            key(rule.id) {
                EditorCard("Rule ${index + 1}", removeLabel = "Remove rule ${index + 1}", onRemove = { onRemove(rule.id) }) {
                    SelectMenuRowBleed("Protocol", PROTO_OPTIONS, rule.proto.key) { k -> onUpdate(rule.copy(proto = FirewallProto.entries.first { it.key == k })) }
                    if (rule.proto != FirewallProto.ICMP) {
                        TnTextField(rule.port, { onUpdate(rule.copy(port = it)) }, label = "Port (any, 80, 200-901, fragment)", isMono = true, hasAutoCapitalize = false)
                    }
                    SelectMenuRowBleed("Match", MATCH_OPTIONS, rule.match.key) { k -> onUpdate(rule.copy(match = FirewallMatch.entries.first { it.key == k })) }
                    TnTextField(rule.value, { onUpdate(rule.copy(value = it)) }, label = matchLabel(rule.match), isMono = true, hasAutoCapitalize = false)
                    TnTextField(rule.localCidr, { onUpdate(rule.copy(localCidr = it)) }, label = "Local CIDR (optional, for unsafe routes)", isMono = true, hasAutoCapitalize = false)
                    TnTextField(rule.caName, { onUpdate(rule.copy(caName = it)) }, label = "Issuing CA name (optional)", hasAutoCapitalize = false)
                    TnTextField(rule.caSha, { onUpdate(rule.copy(caSha = it)) }, label = "Issuing CA fingerprint (optional)", error = errors[rule.id], isMono = true, hasAutoCapitalize = false)
                }
            }
        }
        Spacer(modifier = Modifier.height(TnSpace.md))
        PrimaryButton("Add rule", onAdd, variant = ButtonVariant.SECONDARY)
    }
}

private fun matchLabel(match: FirewallMatch): String = when (match) {
    FirewallMatch.GROUPS -> "Groups (comma separated, all required)"
    FirewallMatch.CIDR -> "Remote CIDR (any, 0.0.0.0/0, ::/0)"
    FirewallMatch.HOST -> "Host name (any for all)"
    FirewallMatch.GROUP -> "Group (any for all)"
}

@Composable
private fun SelectMenuRowBleed(title: String, options: List<SelectOption>, selectedKey: String, onSelect: (String) -> Unit) {
    Column(modifier = Modifier.bleed(TnSpace.md)) { SelectMenuRow(title, options, selectedKey, onSelect) }
}

/** tun.unsafe_routes: subnets reachable through a nebula node that carries them in its certificate. */
@Composable
fun UnsafeRouteEditor(
    routes: List<UnsafeRoute>,
    errors: Map<String, String>,
    onAdd: () -> Unit,
    onUpdate: (UnsafeRoute) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        EditorTitle("Unsafe routes")
        routes.forEachIndexed { index, route ->
            key(route.id) {
                EditorCard("Route ${index + 1}", removeLabel = "Remove route ${index + 1}", onRemove = { onRemove(route.id) }) {
                    TnTextField(route.route, { onUpdate(route.copy(route = it)) }, label = "Route (CIDR)", placeholder = "172.16.1.0/24", isMono = true, hasAutoCapitalize = false)
                    TnTextField(route.via, { onUpdate(route.copy(via = it)) }, label = "Via (nebula IP of the gateway)", placeholder = "fd00:1234:5678::99", isMono = true, hasAutoCapitalize = false)
                    TnTextField(route.mtu, { onUpdate(route.copy(mtu = it)) }, label = "MTU (empty = tunnel MTU)", isNumeric = true)
                    TnTextField(route.metric, { onUpdate(route.copy(metric = it)) }, label = "Metric (empty = 0)", error = errors[route.id], isNumeric = true)
                    Column(modifier = Modifier.bleed(TnSpace.md)) {
                        SwitchRow(TnIcon.SETTINGS, "Install in the routing table", route.isInstalled, { onUpdate(route.copy(isInstalled = !route.isInstalled)) }, subtitle = "Off keeps the route in nebula only")
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(TnSpace.md))
        PrimaryButton("Add unsafe route", onAdd, variant = ButtonVariant.SECONDARY)
    }
}

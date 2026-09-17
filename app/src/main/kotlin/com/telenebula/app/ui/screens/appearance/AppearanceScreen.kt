package com.telenebula.app.ui.screens.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.ColorThemes
import com.telenebula.app.ui.theme.ThemeResolver
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnRow
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType
import com.telenebula.core.model.ThemeMode

@Composable
fun AppearanceScreen(viewModel: AppearanceViewModel) {
    val prefs by viewModel.state.collectAsStateWithLifecycle()
    Screen(title = "Appearance", onBack = viewModel::goBack) {
        ModeSection(prefs.themeMode, viewModel::setMode)
        ColorThemeSection(prefs.colorTheme, prefs.customAccent, viewModel.swatches, viewModel::setColorTheme, viewModel::setCustomAccent)
        PreviewSection()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = TnType.small.copy(letterSpacing = 0.6.sp),
        color = TnTheme.colors.textMuted,
        modifier = Modifier.padding(bottom = TnSpace.sm, start = TnSpace.xs, end = TnSpace.xs),
    )
}

@Composable
private fun ModeSection(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val colors = TnTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg).padding(top = TnSpace.xl)) {
        SectionTitle("Mode")
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadius.md)).background(colors.surfaceRaised).padding(TnSpace.xs),
        ) {
            for ((option, label) in listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")) {
                val isSelected = option == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(TnRadius.sm))
                        .background(if (isSelected) colors.surface else Color.Transparent)
                        .clickable(role = Role.RadioButton) { onSelect(option) }
                        .semantics {
                            contentDescription = label
                            selected = isSelected
                        }
                        .padding(vertical = TnSpace.sm + 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = if (isSelected) TnType.body.copy(fontWeight = FontWeight.Medium) else TnType.body, color = if (isSelected) colors.text else colors.textMuted)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorThemeSection(colorTheme: String, customAccent: String, swatches: List<Color>, onTheme: (String) -> Unit, onCustom: (Color) -> Unit) {
    val colors = TnTheme.colors
    val isCustom = colorTheme == ColorThemes.CUSTOM
    val custom = remember(customAccent) { ThemeResolver.parseHex(customAccent) ?: ColorThemes.defaultAccent }
    Section(title = "Colour theme") {
        for (option in ColorThemes.options) {
            row {
                ThemeRow(option.label, option.accent, isSelected = option.key == colorTheme, onClick = { onTheme(option.key) })
            }
        }
        row {
            Column {
                ThemeRow("Custom", custom, isSelected = isCustom, onClick = null)
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(start = TnSpace.lg, end = TnSpace.lg, bottom = TnSpace.lg),
                    horizontalArrangement = Arrangement.spacedBy(TnSpace.sm),
                    verticalArrangement = Arrangement.spacedBy(TnSpace.sm),
                ) {
                    for (swatch in swatches) {
                        val isActive = isCustom && swatch == custom
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .then(if (isActive) Modifier.border(3.dp, colors.text, CircleShape) else Modifier)
                                .clickable(role = Role.RadioButton) { onCustom(swatch) }
                                .semantics {
                                    contentDescription = "Colour ${ThemeResolver.toHex(swatch)}"
                                    selected = isActive
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeRow(label: String, accent: Color, isSelected: Boolean, onClick: (() -> Unit)?) {
    val colors = TnTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.RadioButton, onClick = onClick) else Modifier)
            .height(TnRow.height)
            .padding(horizontal = TnSpace.lg)
            .semantics {
                contentDescription = label
                selected = isSelected
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.lg),
    ) {
        Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(accent))
        Text(label, style = TnType.body, color = colors.text, modifier = Modifier.weight(1f))
        if (isSelected) Icon(TnIcon.CHECK, tint = accent, size = 20.dp)
    }
}

/** Two bubbles rendered with the live theme, so a pick shows immediately. */
@Composable
private fun PreviewSection() {
    val colors = TnTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg).padding(top = TnSpace.xl)) {
        SectionTitle("Preview")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TnRadius.lg))
                .border(Dp.Hairline, colors.hairline, RoundedCornerShape(TnRadius.lg))
                .padding(TnSpace.lg),
            verticalArrangement = Arrangement.spacedBy(TnSpace.sm),
        ) {
            Bubble("Incoming message", colors.bubbleIn, colors.text, Alignment.Start)
            Bubble("Your reply", colors.bubbleOut, colors.onBubbleOut, Alignment.End)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Bubble(text: String, background: Color, textColor: Color, align: Alignment.Horizontal) {
    Box(
        modifier = Modifier
            .align(align)
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(TnRadius.lg))
            .background(background)
            .padding(horizontal = TnSpace.md, vertical = TnSpace.sm),
    ) { Text(text, style = TnType.body, color = textColor) }
}

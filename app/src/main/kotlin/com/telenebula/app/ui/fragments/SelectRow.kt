package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/** One choice of a [SelectRow]; a [swatch] renders a colour dot instead of the label. */
data class SelectOption(val key: String, val label: String, val swatch: Color? = null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectRow(title: String, options: List<SelectOption>, selectedKey: String, onSelect: (String) -> Unit) {
    val colors = TnTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg, vertical = TnSpace.md),
        verticalArrangement = Arrangement.spacedBy(TnSpace.sm),
    ) {
        Text(title, style = TnType.body, color = colors.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(TnSpace.sm), verticalArrangement = Arrangement.spacedBy(TnSpace.sm)) {
            for (option in options) {
                val isSelected = option.key == selectedKey
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSelected) colors.accentSoft else colors.surfaceRaised)
                        .clickable(role = Role.Button) { onSelect(option.key) }
                        .semantics {
                            contentDescription = "$title: ${option.label}"
                            selected = isSelected
                        }
                        .then(
                            if (option.swatch != null) Modifier.padding(horizontal = 9.dp, vertical = 7.dp)
                            else Modifier.padding(horizontal = TnSpace.md, vertical = 6.dp),
                        ),
                ) {
                    if (option.swatch != null) {
                        Box(modifier = Modifier.size(20.dp).clip(CircleShape).background(option.swatch))
                    } else {
                        Text(
                            option.label,
                            style = TnType.small.copy(fontWeight = FontWeight.Medium),
                            color = if (isSelected) colors.accent else colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

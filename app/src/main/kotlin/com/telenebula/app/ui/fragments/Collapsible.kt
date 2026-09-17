package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnRow
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/** A titled row with a chevron; its content renders below while open. */
@Composable
fun Collapsible(
    title: String,
    isOpen: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    /** marks the header so a folded section with invalid fields stays visible */
    hasError: Boolean = false,
    /** flat header inside a card instead of a standalone card */
    isNested: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TnTheme.colors
    Column(
        modifier = if (isNested) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                .padding(start = TnSpace.lg, end = TnSpace.lg, top = TnSpace.xl)
                .clip(RoundedCornerShape(TnRadius.lg))
                .background(colors.surface)
        },
    ) {
        if (isNested) HorizontalDivider(thickness = Dp.Hairline, color = colors.hairline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .defaultMinSize(minHeight = TnRow.height)
                .padding(horizontal = TnSpace.lg, vertical = TnSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = TnType.body.copy(fontWeight = FontWeight.Medium), color = if (hasError) colors.danger else colors.text)
                if (subtitle != null) Text(subtitle, style = TnType.small, color = colors.textMuted, modifier = Modifier.padding(top = 2.dp))
            }
            if (hasError) Icon(TnIcon.CLOSE, tint = colors.danger, size = 16.dp)
            Icon(if (isOpen) TnIcon.CHEVRON_DOWN else TnIcon.CHEVRON_RIGHT, tint = colors.textMuted, size = 20.dp)
        }
        if (isOpen) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = TnSpace.lg, end = TnSpace.lg, bottom = TnSpace.lg), content = content)
        }
    }
}

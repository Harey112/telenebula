package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnColors
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnRow
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/** DANGER for destructive rows; SUCCESS and INFO only for a live status (a peer online, or merely reachable). */
enum class RowTone { DEFAULT, DANGER, SUCCESS, INFO }

fun TnColors.toneColor(tone: RowTone): Color = when (tone) {
    RowTone.DANGER -> danger
    RowTone.SUCCESS -> success
    RowTone.INFO -> info
    RowTone.DEFAULT -> accent
}

@Composable
private fun IconTile(icon: TnIcon, color: Color) {
    val colors = TnTheme.colors
    Box(
        modifier = Modifier
            .size(TnRow.iconTile)
            .clip(RoundedCornerShape(TnRadius.sm))
            .background(lerp(color, colors.surface, 0.84f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, tint = color, size = 18.dp)
    }
}

@Composable
private fun RowTexts(title: String, subtitle: String?, titleColor: Color, modifier: Modifier) {
    val colors = TnTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        Text(title, style = TnType.body, color = titleColor)
        if (subtitle != null) Text(subtitle, style = TnType.small, color = colors.textMuted)
    }
}

@Composable
fun SettingRow(
    icon: TnIcon,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    tone: RowTone = RowTone.DEFAULT,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = TnTheme.colors
    val color = colors.toneColor(tone)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .defaultMinSize(minHeight = TnRow.height)
            .padding(horizontal = TnSpace.lg, vertical = TnSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
    ) {
        IconTile(icon, color)
        RowTexts(title, subtitle, if (tone == RowTone.DANGER) colors.danger else colors.text, Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun SwitchRow(
    icon: TnIcon,
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    subtitle: String? = null,
    tone: RowTone = RowTone.DEFAULT,
    isEnabled: Boolean = true,
) {
    val colors = TnTheme.colors
    val color = colors.toneColor(tone)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TnRow.height)
            .padding(horizontal = TnSpace.lg, vertical = TnSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
    ) {
        IconTile(icon, color)
        RowTexts(title, subtitle, colors.text, Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = isEnabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.surface,
                checkedTrackColor = color,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = colors.surface,
                uncheckedTrackColor = colors.hairline,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

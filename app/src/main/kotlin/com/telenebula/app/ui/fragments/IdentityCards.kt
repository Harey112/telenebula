package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
private fun Card(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TnSpace.lg)
            .padding(top = TnSpace.sm)
            .clip(RoundedCornerShape(TnRadius.lg))
            .background(TnTheme.colors.surface)
            .padding(TnSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.lg),
    ) { content() }
}

@Composable
fun ProfileCard(name: String, overlayIp: String, isOnline: Boolean, onShowQr: () -> Unit) {
    val colors = TnTheme.colors
    Card {
        Avatar(name = name, size = 64.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = TnType.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(overlayIp, style = TnType.caption.copy(fontFamily = FontFamily.Monospace), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (isOnline) "online" else "offline", style = TnType.caption, color = if (isOnline) colors.accent else colors.textMuted, modifier = Modifier.padding(top = 2.dp))
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(TnRadius.md))
                .background(colors.accentSoft)
                .clickable(role = Role.Button, onClick = onShowQr)
                .semantics { contentDescription = "Show my QR code" },
            contentAlignment = Alignment.Center,
        ) { Icon(TnIcon.QR, tint = colors.accent, size = 22.dp) }
    }
}

@Composable
fun TunnelCard(isRunning: Boolean, isBusy: Boolean, onToggle: () -> Unit) {
    val colors = TnTheme.colors
    Card {
        Column(modifier = Modifier.weight(1f)) {
            Text("Nebula Tunnel", style = TnType.title, color = colors.text)
            Text(if (isRunning) "Connected — you are reachable" else "Disconnected", style = TnType.small, color = colors.textMuted)
        }
        Switch(
            checked = isRunning,
            onCheckedChange = { onToggle() },
            enabled = !isBusy,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.surface,
                checkedTrackColor = colors.success,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = colors.surface,
                uncheckedTrackColor = colors.hairline,
                uncheckedBorderColor = Color.Transparent,
            ),
            modifier = Modifier.semantics { contentDescription = "Nebula tunnel" },
        )
    }
}

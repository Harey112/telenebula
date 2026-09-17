package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/** Inline validation or failure text on a soft danger surface; nothing when the message is empty. */
@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    if (message.isEmpty()) return
    val colors = TnTheme.colors
    Box(
        modifier = modifier
            .then(if (onDismiss != null) Modifier.clickable(role = Role.Button, onClickLabel = "Dismiss", onClick = onDismiss) else Modifier)
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(lerp(colors.danger, colors.surface, 0.86f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(message, style = TnType.small, color = colors.danger)
    }
}

/** Tiny inline loading indicator. */
@Composable
fun Spinner(size: Dp = 12.dp, color: Color? = null) {
    CircularProgressIndicator(modifier = Modifier.size(size), strokeWidth = 1.5.dp, color = color ?: TnTheme.colors.textMuted)
}

/** One tab of the bottom bar: pill behind the active icon, caption label. */
@Composable
fun TabButton(icon: TnIcon, label: String, isFocused: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TnTheme.colors
    Column(
        modifier = modifier
            // the pill behind the icon is the only pressed feedback; no ripple over the whole tab
            .clickable(interactionSource = null, indication = null, role = Role.Tab, onClick = onClick)
            .padding(vertical = TnSpace.sm)
            .semantics {
                contentDescription = label
                selected = isFocused
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(32.dp)
                .clip(CircleShape)
                .background(if (isFocused) colors.accentSoft else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, tint = if (isFocused) colors.accent else colors.textMuted)
        }
        Text(
            label,
            style = TnType.caption.copy(fontWeight = FontWeight.Medium),
            color = if (isFocused) colors.accent else colors.textMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Floating action button; the parent positions it (bottom-end of its Box). */
@Composable
fun Fab(icon: TnIcon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TnTheme.colors
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(TnRadius.lg))
            .background(colors.accent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, tint = colors.onAccent)
    }
}

package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/** The call screen is always dark over video, so these stay fixed (the sanctioned token exception). */
object CallPalette {
    val green = Color(0xFF34C759)
    val red = Color(0xFFFF3B30)
    val neutral = Color(0x21FFFFFF)
    val ink = Color(0xFFFFFFFF)
    val inkMuted = Color(0x99FFFFFF)
    val inkDim = Color(0xB0FFFFFF)
    val scrim = Color(0x59000000)
    val dim = Color(0xB8000000)
    val ground = Color(0xFF0F1216)
    val tile = Color(0xFF151B22)
    val bannerGreen = Color(0xFF21A55E)
    val pressedInk = Color(0xFF0A0A0A)
}

/** One call control: a round button with a label under it. Translucent, white when active, or filled with [color]. */
@Composable
fun RoundControl(icon: TnIcon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, isActive: Boolean = false, color: Color? = null, isEnabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .graphicsLayer {
                    val s = if (pressed) 0.94f else 1f
                    scaleX = s
                    scaleY = s
                    alpha = when {
                        !isEnabled -> 0.35f
                        pressed -> 0.55f
                        else -> 1f
                    }
                }
                .clip(CircleShape)
                .background(color ?: if (isActive) CallPalette.ink else CallPalette.neutral)
                .clickable(interactionSource = interaction, indication = null, enabled = isEnabled, role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = label
                    selected = isActive
                },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, tint = if (isActive && color == null) CallPalette.pressedInk else CallPalette.ink, size = 24.dp) }
        Text(label, style = TnType.caption, color = CallPalette.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Initial-letter avatar shown whenever a call party has no live video (fixed palette, same hash as the floating window). */
@Composable
fun CallAvatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    val color = remember(name) {
        var hash = 0
        for (c in name) hash = hash * 31 + c.code
        val index = if (hash == Int.MIN_VALUE) 0 else kotlin.math.abs(hash) % AVATAR_PALETTE.size
        AVATAR_PALETTE[index]
    }
    val fontSize = with(LocalDensity.current) { (size * 0.4f).toSp() }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color).semantics { contentDescription = "Avatar for $name" },
        contentAlignment = Alignment.Center,
    ) { Text(name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?", style = TextStyle(color = CallPalette.ink, fontSize = fontSize)) }
}

private val AVATAR_PALETTE = listOf(Color(0xFFE17076), Color(0xFF7BC862), Color(0xFF65AADD), Color(0xFFA695E7), Color(0xFFEE7AAE), Color(0xFF6EC9CB), Color(0xFFFAA774))

/** One-time explanation before sending the user to "Display over other apps". */
@Composable
fun OverlayPermissionSheet(isVisible: Boolean, onAllow: () -> Unit, onDecline: () -> Unit) {
    val colors = TnTheme.colors
    TnBottomSheet(isVisible = isVisible, title = "Keep the video call floating", onClose = onDecline) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(modifier = Modifier.size(54.dp).clip(CircleShape).background(colors.surfaceRaised), contentAlignment = Alignment.Center) { Icon(TnIcon.VIDEO, tint = colors.text, size = 26.dp) }
            Text(
                "A minimised video call is shown in a small window on top of whatever you are doing — that window needs “Display over other apps”. Without it the call keeps running, but there is nothing to watch until you come back.",
                style = TnType.small.copy(fontSize = 14.5.sp, lineHeight = 20.sp),
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            PrimaryButton(label = "Allow in Settings", onClick = onAllow)
            Text(
                "Not now",
                style = TnType.body.copy(fontWeight = FontWeight.Medium),
                color = colors.textMuted,
                modifier = Modifier.clickable(role = Role.Button, onClick = onDecline).padding(vertical = 10.dp).semantics { contentDescription = "Not now" },
            )
        }
    }
}

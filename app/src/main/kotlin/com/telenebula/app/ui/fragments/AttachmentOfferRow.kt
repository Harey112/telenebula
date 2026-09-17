package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.platform.Format
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/**
 * A large file the peer is offering. Nothing has been transferred yet: the sender is waiting
 * on this answer, with no timeout, so the row stays until someone decides.
 *
 * [isAffordable] false means the file will not fit on this device. Accept is still shown, but
 * the core turns it into a refusal, so the sender learns the reason instead of watching a
 * transfer die part way.
 */
@Composable
fun AttachmentOfferRow(
    name: String,
    sizeBytes: Long,
    isAffordable: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    ink: Color = TnTheme.colors.text,
    inkMuted: Color = TnTheme.colors.textMuted,
    inkSurface: Color = TnTheme.colors.surfaceRaised,
) {
    val colors = TnTheme.colors
    Column(modifier = modifier.widthIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AttachmentLabel(
            name = name,
            detail = if (isAffordable) "${Format.bytes(sizeBytes)} · waiting for you" else "${Format.bytes(sizeBytes)} · not enough space",
            ink = ink,
            detailColor = if (isAffordable) inkMuted else colors.danger,
            tileColor = inkSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Accept", colors.accent, colors.onAccent, "Accept $name", onClick = onAccept)
            Pill("Decline", inkSurface, ink, "Decline $name", onClick = onDecline)
        }
    }
}

/** Icon tile, file name and one line of detail: the head every attachment row in a bubble shares. */
@Composable
fun AttachmentLabel(
    name: String,
    detail: String,
    ink: Color,
    detailColor: Color,
    tileColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(tileColor), contentAlignment = Alignment.Center) {
            Icon(TnIcon.FILE, tint = ink, size = 20.dp)
        }
        Column(modifier = Modifier.widthIn(max = 190.dp)) {
            Text(name, style = TnType.small.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = TnType.caption.copy(fontSize = 11.5.sp), color = detailColor)
        }
    }
}

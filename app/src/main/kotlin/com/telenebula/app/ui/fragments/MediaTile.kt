package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.telenebula.app.platform.Format
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnRow
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType
import com.telenebula.core.model.ChatLink
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.MessageKind

/** A square thumbnail for an image, or a file/play glyph on a raised surface; [moreCount] > 0 veils it with "+N". */
@Composable
fun MediaTile(msg: ChatMessage, modifier: Modifier = Modifier, onClick: () -> Unit, showName: Boolean = false, moreCount: Int = 0) {
    val colors = TnTheme.colors
    val source = remember(msg.attachment) { msg.attachment?.mediaSource() }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(TnRadius.sm))
            .background(colors.surfaceRaised)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (moreCount > 0) "$moreCount more, open all media" else msg.attachment?.name ?: "Attachment" },
        contentAlignment = Alignment.Center,
    ) {
        if (msg.kind == MessageKind.IMAGE && source != null) {
            CachedImage(source, modifier = Modifier.fillMaxSize())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TnSpace.xs), modifier = Modifier.padding(TnSpace.sm)) {
                Icon(if (msg.kind == MessageKind.VIDEO) TnIcon.PLAY else TnIcon.FILE, tint = colors.textMuted, size = if (showName) 24.dp else 22.dp)
                if (showName) Text(msg.attachment?.name.orEmpty(), style = TnType.caption, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        if (moreCount > 0) {
            Box(modifier = Modifier.fillMaxSize().background(colors.overlay), contentAlignment = Alignment.Center) {
                Text("+$moreCount", style = TnType.title, color = colors.onAccent)
            }
        }
    }
}

@Composable
fun LinkRow(link: ChatLink, onClick: () -> Unit, maxLines: Int = 1) {
    val colors = TnTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = link.url }
            .defaultMinSize(minHeight = TnRow.height)
            .padding(horizontal = TnSpace.lg, vertical = TnSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
    ) {
        Icon(TnIcon.LINK, tint = colors.accent, size = 18.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(link.url, style = TnType.body, color = colors.accent, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            Text(Format.listTime(link.ts), style = TnType.caption, color = colors.textMuted)
        }
    }
}

package com.telenebula.app.ui.fragments

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.platform.Format
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRow
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType
import com.telenebula.core.model.ChatSummary
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageStatus

/** One chat in the list; [ChatSummary] is a stable data class so unchanged rows skip recomposition. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListRow(chat: ChatSummary, onClick: (String) -> Unit, onLongClick: (String) -> Unit) {
    val colors = TnTheme.colors
    val muted = ContactLabels.isMuted(chat.muteUntil)
    val badgeCount = maxOf(chat.unread, if (chat.isMarkedUnread) 1 else 0)
    val label = buildString {
        append("Chat with ").append(chat.name)
        if (chat.unread > 0) append(", ").append(chat.unread).append(" unread")
        if (chat.pinnedAt != null) append(", pinned")
        if (muted) append(", muted")
        if (chat.isBlocked) append(", blocked")
        if (chat.isMarkedUnread) append(", marked unread")
        lastStatusPhrase(chat)?.let { append(", ").append(it) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(role = Role.Button, onClick = { onClick(chat.ip) }, onLongClick = { onLongClick(chat.ip) })
            .semantics { contentDescription = label }
            .padding(horizontal = TnSpace.lg, vertical = TnSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
    ) {
        Avatar(chat.name, size = TnRow.avatar)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chat.name, style = TnType.body.copy(fontWeight = FontWeight.Medium), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = TnSpace.sm))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TnSpace.xs)) {
                    if (muted) Icon(TnIcon.BELL_OFF, tint = colors.textMuted, size = 13.dp)
                    if (chat.pinnedAt != null) Icon(TnIcon.PIN, tint = colors.textMuted, size = 13.dp)
                    chat.lastTs?.let { Text(Format.listTime(it), style = TnType.caption, color = colors.textMuted) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    if (chat.isBlocked) "Blocked" else chat.lastBody ?: chat.ip,
                    style = TnType.small,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = TnSpace.sm),
                )
                StatusSlot(chat, badgeCount, muted)
            }
        }
    }
}

/** The row sets its own contentDescription, so the glyphs inside it are never announced. */
private fun lastStatusPhrase(chat: ChatSummary): String? = when {
    chat.lastDirection == MessageDirection.OUT -> when {
        chat.lastSendStatus == MessageActionStatus.FAILED -> "last message not sent"
        chat.lastSendStatus == MessageActionStatus.CANCELLED -> "last message cancelled"
        chat.lastSeenAt != null -> "last message seen"
        chat.lastSendStatus == MessageActionStatus.WAITING -> "last message waiting to be accepted"
        chat.lastSendStatus == MessageActionStatus.PENDING || chat.lastStatus == MessageStatus.PENDING ->
            "last message on queue"
        chat.lastStatus == MessageStatus.CANCELLED -> "last transfer cancelled"
        else -> "last message delivered"
    }
    chat.lastDirection == MessageDirection.IN -> when (chat.lastStatus) {
        MessageStatus.RECEIVING -> "file still arriving"
        MessageStatus.OFFERED -> "file waiting for your answer"
        MessageStatus.DECLINED -> "file declined"
        MessageStatus.CANCELLED -> "file transfer cancelled"
        else -> null
    }
    else -> null
}

@Composable
private fun StatusSlot(chat: ChatSummary, badgeCount: Int, muted: Boolean) {
    Box(modifier = Modifier.defaultMinSize(minWidth = 18.dp).height(18.dp), contentAlignment = Alignment.Center) {
        when {
            badgeCount > 0 -> Badge(badgeCount, muted)
            chat.lastDirection == MessageDirection.OUT -> OutboundStatus(chat.name, chat.lastStatus, chat.lastSeenAt, chat.lastSendStatus)
            chat.lastDirection == MessageDirection.IN -> InboundStatus(chat.lastStatus)
        }
    }
}

@Composable
private fun Badge(count: Int, muted: Boolean) {
    val colors = TnTheme.colors
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 18.dp)
            .height(18.dp)
            .clip(CircleShape)
            .background(if (muted) colors.surfaceRaised else colors.accent)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = TnType.caption.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
            color = if (muted) colors.textMuted else colors.onAccent,
        )
    }
}

// the two endings come first: neither moves the message off PENDING
@Composable
private fun OutboundStatus(peerName: String, status: MessageStatus?, seenAt: Long?, sendStatus: MessageActionStatus?) {
    val colors = TnTheme.colors
    when {
        sendStatus == MessageActionStatus.FAILED ->
            Icon(TnIcon.CLOSE, tint = colors.danger, size = 14.dp, contentDescription = "Not sent")
        sendStatus == MessageActionStatus.CANCELLED ->
            Icon(TnIcon.CLOSE, tint = colors.textMuted, size = 14.dp, contentDescription = "Sending cancelled")
        seenAt != null ->
            Box(modifier = Modifier.semantics { contentDescription = "Seen by $peerName" }) { Avatar(peerName, size = 14.dp) }
        sendStatus == MessageActionStatus.WAITING ->
            Icon(TnIcon.HOURGLASS, tint = colors.textMuted, size = 14.dp, contentDescription = "Waiting for $peerName to accept the file")
        sendStatus == MessageActionStatus.PENDING || status == MessageStatus.PENDING ->
            Icon(TnIcon.HOURGLASS, tint = colors.textMuted, size = 14.dp, contentDescription = "On queue")
        status == MessageStatus.CANCELLED ->
            Icon(TnIcon.CLOSE, tint = colors.textMuted, size = 14.dp, contentDescription = "Transfer cancelled")
        else -> Icon(TnIcon.CHECK, tint = colors.accent, size = 14.dp, contentDescription = "Delivered")
    }
}

@Composable
private fun InboundStatus(status: MessageStatus?) {
    val colors = TnTheme.colors
    when (status) {
        MessageStatus.RECEIVING -> Spinner(size = 13.dp)
        MessageStatus.OFFERED ->
            Icon(TnIcon.HOURGLASS, tint = colors.textMuted, size = 14.dp, contentDescription = "File waiting for your answer")
        MessageStatus.DECLINED ->
            Icon(TnIcon.CLOSE, tint = colors.textMuted, size = 14.dp, contentDescription = "File declined")
        MessageStatus.CANCELLED ->
            Icon(TnIcon.CLOSE, tint = colors.textMuted, size = 14.dp, contentDescription = "File transfer cancelled")
        else -> Unit
    }
}

/** A contact in a picker or list: avatar, "label · detail" (the detail in italics), address. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactRow(name: String, ip: String, onClick: (String) -> Unit, onLongClick: ((String) -> Unit)? = null, detail: String? = null) {
    val colors = TnTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(role = Role.Button, onClick = { onClick(ip) }, onLongClick = onLongClick?.let { { it(ip) } })
            .semantics { contentDescription = if (detail != null) "Contact $name, $detail" else "Contact $name" }
            .padding(horizontal = 14.dp, vertical = TnSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
    ) {
        Avatar(name, size = 46.dp)
        Column(modifier = Modifier.weight(1f)) {
            val title = remember(name, detail) {
                buildAnnotatedString {
                    append(name)
                    if (detail != null) {
                        append(" · ")
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal)) { append(detail) }
                    }
                }
            }
            Text(title, style = TnType.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ip != name) {
                Text(ip, style = TnType.caption.copy(fontSize = 12.5.sp, fontFamily = FontFamily.Monospace), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }
        }
    }
}

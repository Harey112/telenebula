package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.platform.ActionQueue
import com.telenebula.app.platform.Format
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType

@Composable
fun DateSeparator(ts: Long) {
    val colors = TnTheme.colors
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = TnSpace.sm), contentAlignment = Alignment.Center) {
        Text(
            Format.datePill(ts),
            style = TnType.caption.copy(fontWeight = FontWeight.Medium),
            color = colors.textMuted,
            modifier = Modifier.clip(CircleShape).background(colors.surfaceRaised).padding(horizontal = TnSpace.md, vertical = TnSpace.xs),
        )
    }
}

/** Where reading left off: everything below this line arrived unread. */
@Composable
fun UnreadSeparator() {
    val colors = TnTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = TnSpace.sm).semantics { contentDescription = "Unread messages below" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = colors.accent)
        Text(
            "Unread",
            style = TnType.caption.copy(fontWeight = FontWeight.Medium),
            color = colors.accent,
            modifier = Modifier.padding(horizontal = TnSpace.sm),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = colors.accent)
    }
}

class ReactionEntry(val ip: String, val emoji: String, val name: String, val isMine: Boolean)

/** Who reacted to a message; the user's own reaction can be removed here. */
@Composable
fun ReactionsList(entries: List<ReactionEntry>, onRemove: () -> Unit) {
    val colors = TnTheme.colors
    if (entries.isEmpty()) {
        Text("No reactions.", style = TnType.small, color = colors.textMuted, modifier = Modifier.padding(vertical = 16.dp))
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        items(entries, key = { it.ip }) { item ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(item.name, size = 40.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, style = TnType.body.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Medium), color = colors.text)
                    if (item.isMine) {
                        Text(
                            "Tap to remove",
                            style = TnType.caption.copy(fontSize = 12.5.sp),
                            color = colors.accent,
                            modifier = Modifier.padding(top = 2.dp).clickable(role = Role.Button, onClick = onRemove).semantics { contentDescription = "Remove your reaction" },
                        )
                    }
                }
                Text(item.emoji, style = TextStyle(fontSize = 24.sp))
            }
        }
    }
}

private fun MessageAction.icon(): TnIcon = when (type) {
    MessageActionType.SEND -> TnIcon.SEND
    MessageActionType.REACT -> TnIcon.EMOJI
    MessageActionType.EDIT -> TnIcon.PENCIL
    MessageActionType.DELETE -> TnIcon.TRASH
    MessageActionType.SEEN -> TnIcon.EYE
    MessageActionType.ATT_ACCEPT -> TnIcon.CHECK
    MessageActionType.ATT_DECLINE, MessageActionType.ATT_CANCEL -> TnIcon.CLOSE
    MessageActionType.ATT_ERROR -> TnIcon.INFO
    MessageActionType.UNKNOWN -> TnIcon.CIRCLE
}

fun MessageAction.label(): String = when (type) {
    MessageActionType.SEND -> "Send message"
    MessageActionType.REACT -> (if (payload.remove == true) "Remove react " else "React ") + payload.emoji.orEmpty()
    MessageActionType.EDIT -> "Edit message"
    MessageActionType.DELETE -> "Delete for everyone"
    MessageActionType.SEEN -> "Seen receipt"
    MessageActionType.ATT_ACCEPT -> "Accept file"
    MessageActionType.ATT_DECLINE -> "Decline file"
    MessageActionType.ATT_CANCEL -> "Withdraw file"
    MessageActionType.ATT_ERROR -> "Report a transfer problem"
    // written by a newer build than this one; it is shown, never run
    MessageActionType.UNKNOWN -> "Unsupported action"
}.trim()

/** Full activity history of one message. Running actions can be cancelled; failed/cancelled ones retried. */
@Composable
fun ActionsList(
    actions: List<MessageAction>,
    /** the peer answered its last probe: what is pending is moving rather than sitting on the queue */
    isPeerReachable: Boolean,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    val colors = TnTheme.colors
    if (actions.isEmpty()) {
        Text("No activity on this message yet.", style = TnType.small, color = colors.textMuted, modifier = Modifier.padding(vertical = 16.dp))
        return
    }
    val runningId = ActionQueue.runningActionId(actions)
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
        items(actions.asReversed(), key = { it.id }) { item ->
            val isRunning = item.id == runningId
            val (statusText, statusColor) = when (item.status) {
                MessageActionStatus.PENDING -> when {
                    isRunning && isPeerReachable -> "In progress" to colors.textMuted
                    else -> "${item.label()} on queue" to colors.textMuted
                }
                MessageActionStatus.WAITING -> "Waiting for them to accept" to colors.textMuted
                MessageActionStatus.FAILED -> (item.payload.failReason?.let { "Refused: $it" } ?: "Refused") to colors.danger
                MessageActionStatus.CANCELLED -> "Cancelled" to colors.textMuted
                MessageActionStatus.SUCCESS -> "Done" to colors.success
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.size(34.dp).clip(CircleShape).background(colors.surfaceRaised), contentAlignment = Alignment.Center) {
                    Icon(item.icon(), tint = colors.text, size = 17.dp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.label(), style = TnType.body, color = colors.text)
                    Text("$statusText · ${Format.listTime(item.updatedAt)}", style = TnType.caption.copy(fontSize = 12.5.sp), color = statusColor, modifier = Modifier.padding(top = 1.dp))
                }
                when (item.status) {
                    MessageActionStatus.PENDING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isRunning && isPeerReachable) Spinner(size = 16.dp) else Icon(TnIcon.HOURGLASS, tint = colors.textMuted, size = 15.dp)
                        Pill("Cancel", colors.surfaceRaised, colors.text, "Cancel ${item.label()}") { onCancel(item.id) }
                    }
                    MessageActionStatus.WAITING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(TnIcon.HOURGLASS, tint = colors.textMuted, size = 15.dp)
                        Pill("Cancel", colors.surfaceRaised, colors.text, "Cancel ${item.label()}") { onCancel(item.id) }
                    }
                    MessageActionStatus.SUCCESS -> Icon(TnIcon.CHECK, tint = colors.success, size = 18.dp)
                    MessageActionStatus.FAILED, MessageActionStatus.CANCELLED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(TnIcon.CLOSE, tint = if (item.status == MessageActionStatus.FAILED) colors.danger else colors.textMuted, size = 16.dp)
                        Pill("Retry", colors.accent, colors.onAccent, "Retry ${item.label()}", icon = TnIcon.RETRY) { onRetry(item.id) }
                    }
                }
            }
        }
    }
}

@Composable
fun Pill(text: String, background: androidx.compose.ui.graphics.Color, foreground: androidx.compose.ui.graphics.Color, label: String, icon: TnIcon? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, tint = foreground, size = 15.dp)
        Text(text, style = TnType.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium), color = foreground)
    }
}

class ForwardTarget(val ip: String, val label: String)

@Composable
fun ForwardSheet(isVisible: Boolean, contacts: List<ForwardTarget>, onPick: (String) -> Unit, onClose: () -> Unit) {
    val colors = TnTheme.colors
    TnBottomSheet(isVisible = isVisible, title = "Forward to…", onClose = onClose) {
        if (contacts.isEmpty()) {
            Text("No other contacts yet.", style = TnType.small, color = colors.textMuted, modifier = Modifier.padding(vertical = 16.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(contacts, key = { it.ip }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { onPick(item.ip) }
                            .semantics { contentDescription = "Forward to ${item.label}" }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(item.label, size = 40.dp)
                        Column {
                            Text(item.label, style = TnType.body.copy(fontSize = 15.5.sp, fontWeight = FontWeight.Medium), color = colors.text)
                            Text(item.ip, style = TnType.caption.copy(fontSize = 12.5.sp, fontFamily = FontFamily.Monospace), color = colors.textMuted)
                        }
                    }
                }
            }
        }
    }
}

/** What the long-press menu may offer for one message. */
class MessageMenuState(
    val messageId: String,
    val canReply: Boolean,
    val canEdit: Boolean,
    val canCopy: Boolean,
    val canDeleteEveryone: Boolean,
    val canReact: Boolean,
    val canForward: Boolean,
    val myReaction: String?,
)

/** Long-press menu on a message: quick reactions (+ opens the full picker) and actions. */
@Composable
fun MessageActionsMenu(
    state: MessageMenuState?,
    quickReactions: List<String>,
    onClose: () -> Unit,
    onReact: (String) -> Unit,
    onMoreReactions: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onShowActions: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
) {
    val colors = TnTheme.colors
    CenteredOverlay(isVisible = state != null, onDismiss = onClose) {
        if (state == null) return@CenteredOverlay
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(colors.surface)
                .padding(vertical = 6.dp),
        ) {
            if (state.canReact) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (emoji in quickReactions) {
                        val isCurrent = emoji == state.myReaction
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isCurrent) colors.accentSoft else colors.surfaceRaised)
                                .clickable(role = Role.Button) { onReact(emoji) }
                                .semantics { contentDescription = if (isCurrent) "Remove reaction $emoji" else "React $emoji" },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji, style = TextStyle(fontSize = 22.sp)) }
                    }
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(colors.surfaceRaised).clickable(role = Role.Button, onClick = onMoreReactions).semantics { contentDescription = "More reactions" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(TnIcon.PLUS, tint = colors.text, size = 19.dp) }
                }
            }
            if (state.canReply) MenuRow(TnIcon.REPLY, "Reply", onClick = onReply)
            if (state.canEdit) MenuRow(TnIcon.PENCIL, "Edit", onClick = onEdit)
            if (state.canCopy) MenuRow(TnIcon.COPY, "Copy", onClick = onCopy)
            if (state.canForward) MenuRow(TnIcon.FORWARD, "Forward", onClick = onForward)
            MenuRow(TnIcon.LIST, "Actions", onClick = onShowActions)
            MenuRow(TnIcon.TRASH, "Delete for me", isDanger = true, onClick = onDeleteForMe)
            if (state.canDeleteEveryone) MenuRow(TnIcon.TRASH, "Delete for everyone", isDanger = true, onClick = onDeleteForEveryone)
        }
    }
}

@Composable
private fun MenuRow(icon: TnIcon, label: String, isDanger: Boolean = false, onClick: () -> Unit) {
    val colors = TnTheme.colors
    val color = if (isDanger) colors.danger else colors.text
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, tint = color, size = 19.dp)
        Text(label, style = TnType.body.copy(fontSize = 15.5.sp), color = color)
    }
}

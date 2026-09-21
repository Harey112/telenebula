package com.telenebula.app.ui.fragments

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.platform.ActionQueue
import com.telenebula.app.platform.VoicePlayback
import com.telenebula.app.platform.Format
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import com.telenebula.core.model.MessageAttachment
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Cap on per-action indicators in the details row; older ones collapse behind "…". */
private const val MAX_INDICATORS = 5
private const val MEDIA_FALLBACK_ASPECT = 4f / 3f
private val MEDIA_MAX_WIDTH = 216.dp
private val MEDIA_MAX_HEIGHT = 260.dp
private val SWIPE_THRESHOLD = 48.dp
private val SWIPE_MAX = 72.dp

class ReplyPreview(val name: String, val snippet: String)

private fun MessageAction.verb(): String = when (type) {
    MessageActionType.SEND -> "sending"
    MessageActionType.REACT -> if (payload.remove == true) "removing react" else "sending react"
    MessageActionType.EDIT -> "sending edit"
    MessageActionType.DELETE -> "deleting"
    MessageActionType.SEEN -> "sending seen"
    MessageActionType.ATT_ACCEPT -> "accepting file"
    MessageActionType.ATT_DECLINE -> "declining file"
    MessageActionType.ATT_CANCEL -> "withdrawing file"
    MessageActionType.ATT_ERROR -> "reporting a problem"
    MessageActionType.UNKNOWN -> "working"
}

private fun MessageAction.failedVerb(): String = payload.failReason?.let(::failReasonText) ?: when (type) {
    MessageActionType.SEND -> "not sent"
    MessageActionType.REACT -> "react failed"
    MessageActionType.EDIT -> "edit failed"
    MessageActionType.DELETE -> "delete failed"
    MessageActionType.SEEN -> "seen not sent"
    MessageActionType.ATT_ACCEPT -> "accept not sent"
    MessageActionType.ATT_DECLINE -> "decline not sent"
    MessageActionType.ATT_CANCEL -> "withdrawal not sent"
    MessageActionType.ATT_ERROR -> "report not sent"
    MessageActionType.UNKNOWN -> "not sent"
}

/** A refusal the peer actually sent back, rather than the silence of a timeout. */
private fun failReasonText(reason: String): String = when (reason) {
    "no-space" -> "not sent — they're out of storage"
    "declined" -> "declined"
    "cancelled" -> "they cancelled the transfer"
    else -> "not sent"
}

/**
 * An attachment on its way in. It deliberately does not offer to open anything: the file only
 * exists on disk once the last chunk lands, and the percentage lives in the row below.
 */
@Composable
private fun IncomingAttachmentRow(
    name: String,
    sizeBytes: Long,
    ink: Color,
    inkMuted: Color,
    inkSurface: Color,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.widthIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AttachmentLabel(
            name = name,
            detail = "${Format.bytes(sizeBytes)} · receiving",
            ink = ink,
            detailColor = inkMuted,
            tileColor = inkSurface,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Pill("Cancel", inkSurface, ink, "Cancel receiving $name", onClick = onCancel)
    }
}

/** Action states worth a glyph in the collapsed row: still going, parked, or given up. */
private val UNSETTLED = setOf(
    MessageActionStatus.PENDING,
    MessageActionStatus.WAITING,
    MessageActionStatus.FAILED,
)

private class LiveSummary(val text: String, val hasFailure: Boolean)

/**
 * Media keeps its own proportions instead of being cropped to a square. The ratio comes from the
 * sender, so the row reserves the right shape before the file decodes and the timeline does not
 * reflow as it scrolls; anything unmeasured falls back to a neutral landscape. Extremes are
 * clamped so one panorama or one very tall screenshot cannot take over the screen.
 */
private fun MessageAttachment?.aspect(): Float {
    val width = this?.width?.takeIf { it > 0 } ?: return MEDIA_FALLBACK_ASPECT
    val height = this.height?.takeIf { it > 0 } ?: return MEDIA_FALLBACK_ASPECT
    return width.toFloat() / height.toFloat()
}

/**
 * Fits media inside a box at its own ratio, never cropped and never stretched. The sender's
 * dimensions size it before it decodes, then the decoded size corrects it, which is what makes an
 * older message or a sender who never reported dimensions come out right as well.
 */
@Composable
private fun mediaModifier(ratio: Float, isBleeding: Boolean): Modifier {
    val safe = ratio.takeIf { it.isFinite() && it > 0f } ?: MEDIA_FALLBACK_ASPECT
    val size = if (safe >= MEDIA_MAX_WIDTH / MEDIA_MAX_HEIGHT) {
        Modifier.width(MEDIA_MAX_WIDTH).height(MEDIA_MAX_WIDTH / safe)
    } else {
        Modifier.height(MEDIA_MAX_HEIGHT).width(MEDIA_MAX_HEIGHT * safe)
    }
    return size.then(if (isBleeding) Modifier else Modifier.clip(RoundedCornerShape(12.dp)))
}

/**
 * One line of what is still happening to the message: what is moving, what is behind it, what was
 * refused. Nothing counts attempts any more — an action is not tried a fixed number of times, it
 * waits for its peer — so anything not moving reads as being on queue.
 */
private fun liveSummary(
    actions: List<MessageAction>,
    runningId: String?,
    sendProgress: Float?,
    isPeerSending: Boolean,
): LiveSummary? {
    if (actions.isEmpty()) return null
    val parts = ArrayList<String>(4)
    var hasFailure = false
    // parked on an offer nobody has answered: not running, not queued, and not a failure
    if (actions.any { it.type == MessageActionType.SEND && it.status == MessageActionStatus.WAITING }) {
        return LiveSummary("waiting for them to accept", hasFailure = false)
    }
    val running = actions.firstOrNull { it.id == runningId }
    if (running != null) {
        parts.add(
            when {
                running.type == MessageActionType.SEND && sendProgress != null ->
                    "sending… ${(sendProgress * 100).roundToInt()}%"
                isPeerSending -> "${running.verb()}…"
                else -> "${running.label()} on queue"
            },
        )
    }
    var queued = 0
    for (a in actions) if (a.status == MessageActionStatus.PENDING && a.id != runningId) queued++
    if (queued > 0) parts.add(if (queued == 1) "1 on queue" else "$queued on queue")
    for (a in actions) {
        when (a.status) {
            MessageActionStatus.FAILED -> {
                parts.add(a.failedVerb())
                hasFailure = true
            }
            MessageActionStatus.CANCELLED -> a.payload.failReason?.let { parts.add(failReasonText(it)) }
            else -> Unit
        }
    }
    return if (parts.isEmpty()) null else LiveSummary(parts.joinToString(" · "), hasFailure)
}

@Composable
private fun ReceivingProgress(fraction: Float, style: androidx.compose.ui.text.TextStyle) {
    Spinner(size = 11.dp)
    Text("receiving… ${(fraction * 100).roundToInt()}%", style = style, color = TnTheme.colors.textMuted)
}

@Composable
private fun ActionIndicator(action: MessageAction, isRunning: Boolean, isPeerSending: Boolean) {
    val colors = TnTheme.colors
    when (action.status) {
        MessageActionStatus.PENDING -> if (isRunning && isPeerSending) Spinner(size = 11.dp) else Icon(TnIcon.HOURGLASS, tint = colors.textMuted, size = 11.dp)
        // parked on an offer: nothing is being attempted, so no spinner
        MessageActionStatus.WAITING -> Icon(TnIcon.HOURGLASS, tint = colors.textMuted, size = 11.dp)
        MessageActionStatus.FAILED -> Icon(TnIcon.CLOSE, tint = colors.danger, size = 12.dp)
        MessageActionStatus.CANCELLED -> Icon(TnIcon.CLOSE, tint = colors.textMuted, size = 12.dp)
        MessageActionStatus.SUCCESS -> Icon(TnIcon.CHECK, tint = colors.textMuted, size = 12.dp)
    }
}

/**
 * One message. Swipe toward its own side to reply, tap for details, long press for the menu, and
 * double tap an unsent one to try it again now. Every
 * parameter is a value or a stable lambda, so an unchanged row skips recomposition when the list
 * re-queries.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MessageBubble(
    msg: ChatMessage,
    actions: List<MessageAction>,
    textSizeSp: Float,
    /** tighter paddings and spacing (message density preference) */
    isCompact: Boolean,
    /** name + snippet of the message this one replies to (already resolved) */
    repliedPreview: ReplyPreview?,
    /** character ranges of the URLs in [msg]'s body, found once per query by the view model */
    linkRanges: List<IntRange>,
    /** chunked transfer progress 0..1: an upload of ours, or a download still arriving */
    transferPct: Float?,
    /** the whole message is behind a lock until it is revealed */
    isCovered: Boolean,
    /** an incoming transfer this device stopped, as opposed to one the sender withdrew */
    isCancelledByMe: Boolean,
    /** tap-opened details row: time, edited, seen, every action */
    isExpanded: Boolean,
    /** this is the most recent message the peer has seen — show their avatar */
    showSeenAvatar: Boolean,
    peerName: String,
    /** a worker is on this peer right now, so what is queued is actually moving */
    isPeerSending: Boolean,
    onClick: (ChatMessage) -> Unit,
    onLongClick: (ChatMessage) -> Unit,
    onReply: (ChatMessage) -> Unit,
    onClickAttachment: (ChatMessage) -> Unit,
    onCancelTransfer: (ChatMessage) -> Unit,
    onClickReactions: (ChatMessage) -> Unit,
    onClickLink: (String) -> Unit,
    onRetrySend: (ChatMessage) -> Unit,
    onAcceptOffer: (ChatMessage) -> Unit,
    onDeclineOffer: (ChatMessage) -> Unit,
    /** room left on this device, so an offer too large to fit can say so before it is taken */
    freeBytes: Long,
    voicePlayback: VoicePlayback? = null,
    onToggleVoice: (ChatMessage) -> Unit = {},
) {
    val colors = TnTheme.colors
    val mine = msg.direction == MessageDirection.OUT
    val bubbleColor = if (mine) colors.bubbleOut else colors.bubbleIn
    val ink = if (mine) colors.onBubbleOut else colors.text
    val inkMuted = lerp(ink, bubbleColor, 0.35f)
    val inkSurface = lerp(ink, bubbleColor, 0.88f)
    val runningId = remember(actions) { ActionQueue.runningActionId(actions) }
    val live = remember(actions, runningId, transferPct, isPeerSending) { liveSummary(actions, runningId, transferPct, isPeerSending) }
    // an incoming file has no actions of its own, so its progress is read off the message
    val receivingPct = transferPct?.takeIf { !mine && msg.status == MessageStatus.RECEIVING }
    val isSendCancelled = actions.any { it.type == MessageActionType.SEND && it.status == MessageActionStatus.CANCELLED }
    // only a message still on its way, or one that gave up, answers a double tap — everywhere else
    // the gesture stays unbound so a single tap keeps opening the details with no wait
    val sendStatus = actions.firstOrNull { it.type == MessageActionType.SEND }?.status
    val canRetrySend = sendStatus != null && sendStatus != MessageActionStatus.SUCCESS
    val isContentless = msg.isDeleted || msg.status.hasNoFile
    // nothing but the picture: it fills the bubble edge to edge, with the bubble's own corners.
    // With a caption or a quote the media stays inset, so the text keeps its breathing room.
    val isBleeding = !isCovered && !msg.isDeleted && msg.body.isEmpty() && repliedPreview == null &&
        (msg.kind == MessageKind.IMAGE || msg.kind == MessageKind.VIDEO) && msg.attachment?.mediaSource() != null
    val reactionCount = msg.reactions.size

    // swipe-to-reply: a horizontal drag with resistance, toward the side the bubble already sits on —
    // pull an incoming message right, your own left. Releasing past the threshold replies.
    val density = LocalDensity.current
    val maxPx = with(density) { SWIPE_MAX.toPx() }
    val thresholdPx = with(density) { SWIPE_THRESHOLD.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxWidth()) {
        if (!isContentless) {
            Icon(
                TnIcon.REPLY,
                tint = colors.textMuted,
                size = 20.dp,
                modifier = Modifier
                    .align(if (mine) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(start = if (mine) 0.dp else 14.dp, end = if (mine) 14.dp else 0.dp)
                    .graphicsLayer { alpha = (abs(offset.value) / thresholdPx).coerceIn(0f, 1f) },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(isContentless, mine) {
                    if (isContentless) return@pointerInput
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, delta ->
                            val dragged = offset.value + delta * 0.6f
                            val next = if (mine) dragged.coerceIn(-maxPx, 0f) else dragged.coerceIn(0f, maxPx)
                            scope.launch { offset.snapTo(next) }
                        },
                        onDragEnd = {
                            val shouldReply = abs(offset.value) >= thresholdPx
                            scope.launch { offset.animateTo(0f) }
                            if (shouldReply) onReply(msg)
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    )
                }
                .padding(vertical = if (isCompact) 0.5.dp else 2.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth())
                    .alpha(if (isSendCancelled) 0.55f else 1f)
                    .clip(
                        if (isCompact) RoundedCornerShape(14.dp)
                        else RoundedCornerShape(topStart = 17.dp, topEnd = 17.dp, bottomStart = if (mine) 17.dp else 5.dp, bottomEnd = if (mine) 5.dp else 17.dp),
                    )
                    .background(bubbleColor)
                    .combinedClickable(
                        role = Role.Button,
                        onClick = { onClick(msg) },
                        onLongClick = { onLongClick(msg) },
                        onDoubleClick = if (canRetrySend) ({ onRetrySend(msg) }) else null,
                    )
                    .semantics {
                        contentDescription = when {
                            isCovered -> "Covered message, tap to reveal"
                            msg.isDeleted -> "Deleted message"
                            else -> msg.body.ifEmpty { msg.attachment?.name ?: "Attachment" }
                        }
                    }
                    .padding(
                        horizontal = if (isBleeding) 0.dp else if (isCompact) 9.dp else 11.dp,
                        vertical = if (isBleeding) 0.dp else if (isCompact) 4.dp else 7.dp,
                    ),
            ) {
                if (repliedPreview != null && !msg.isDeleted) {
                    Row(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(inkSurface)) {
                        Box(modifier = Modifier.width(3.dp).height(36.dp).background(ink))
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(repliedPreview.name, style = TnType.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(repliedPreview.snippet, style = TnType.caption.copy(fontSize = 12.5.sp), color = inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(Modifier.height(5.dp))
                }
                if (isCovered) {
                    CoverFace(ink, inkMuted, inkSurface, textSizeSp)
                } else if (msg.isDeleted) {
                    Text("Message deleted", style = TnType.body.copy(fontSize = 14.5.sp, fontStyle = FontStyle.Italic), color = inkMuted)
                } else if (msg.status == MessageStatus.OFFERED && !mine) {
                    val size = msg.attachment?.size ?: 0
                    AttachmentOfferRow(
                        name = msg.attachment?.name ?: "File",
                        sizeBytes = size,
                        isAffordable = freeBytes <= 0 || freeBytes > size,
                        onAccept = { onAcceptOffer(msg) },
                        onDecline = { onDeclineOffer(msg) },
                        ink = ink,
                        inkMuted = inkMuted,
                        inkSurface = inkSurface,
                    )
                } else if (msg.status == MessageStatus.RECEIVING) {
                    IncomingAttachmentRow(
                        name = msg.attachment?.name ?: "File",
                        sizeBytes = msg.attachment?.size ?: 0,
                        ink = ink,
                        inkMuted = inkMuted,
                        inkSurface = inkSurface,
                        onCancel = { onCancelTransfer(msg) },
                    )
                } else if (msg.status.hasNoFile) {
                    val what = when {
                        msg.status == MessageStatus.DECLINED -> "Declined"
                        mine || isCancelledByMe -> "Cancelled"
                        else -> "Cancelled by $peerName"
                    }
                    Text(
                        "$what · ${msg.attachment?.name ?: "File"}",
                        style = TnType.small.copy(fontSize = 13.5.sp, fontStyle = FontStyle.Italic),
                        color = inkMuted,
                    )
                } else {
                    val source = remember(msg.attachment) { msg.attachment?.mediaSource() }
                    val voiceAttachment = msg.attachment?.takeIf { it.isVoice }
                    // an unopenable row must not take the tap, or the bubble never opens its details
                    val canOpen = source != null
                    val openModifier = if (canOpen) {
                        Modifier.combinedClickable(role = Role.Button, onClick = { onClickAttachment(msg) }, onLongClick = { onLongClick(msg) })
                    } else {
                        Modifier
                    }
                    when (msg.kind) {
                        MessageKind.IMAGE -> if (source != null) {
                            var ratio by remember(source) { mutableStateOf(msg.attachment.aspect()) }
                            CachedImage(
                                source,
                                contentDescription = "View photo",
                                contentScale = ContentScale.Fit,
                                onIntrinsicSize = { w, h -> if (w > 0 && h > 0) ratio = w.toFloat() / h },
                                modifier = mediaModifier(ratio, isBleeding)
                                    .combinedClickable(role = Role.Image, onClick = { onClickAttachment(msg) }, onLongClick = { onLongClick(msg) }),
                            )
                            if (!isBleeding) Box(Modifier.height(5.dp))
                        }
                        MessageKind.VIDEO -> {
                            var ratio by remember(source) { mutableStateOf(msg.attachment.aspect()) }
                            Box(
                                modifier = mediaModifier(ratio, isBleeding)
                                    .background(inkSurface)
                                    .then(openModifier)
                                    .semantics { contentDescription = if (canOpen) "Play video ${msg.attachment?.name.orEmpty()}" else "Video ${msg.attachment?.name.orEmpty()}, not available" },
                                contentAlignment = Alignment.Center,
                            ) {
                                // a frame out of the file itself; the decoder is registered in TeleNebulaApp
                                if (source != null) {
                                    CachedImage(
                                        source,
                                        modifier = Modifier.fillMaxSize(),
                                        contentDescription = null,
                                        onIntrinsicSize = { w, h -> if (w > 0 && h > 0) ratio = w.toFloat() / h },
                                    )
                                }
                                // scrim so the play badge stays legible over any frame
                                Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                                    Icon(TnIcon.PLAY, tint = Color.White, size = 26.dp)
                                }
                            }
                            if (!isBleeding) Box(Modifier.height(5.dp))
                        }
                        MessageKind.FILE -> if (voiceAttachment != null) VoiceClip(
                            attachment = voiceAttachment,
                            playback = voicePlayback,
                            canPlay = canOpen,
                            ink = ink,
                            inkMuted = inkMuted,
                            inkSurface = inkSurface,
                            onToggle = { onToggleVoice(msg) },
                            onLongClick = { onLongClick(msg) },
                        ) else AttachmentLabel(
                            name = msg.attachment?.name ?: "File",
                            detail = Format.bytes(msg.attachment?.size ?: 0) + if (canOpen) " · Tap to open" else " · not on this device",
                            ink = ink,
                            detailColor = inkMuted,
                            tileColor = inkSurface,
                            modifier = Modifier
                                .widthIn(max = 230.dp)
                                .then(openModifier)
                                .semantics { contentDescription = if (canOpen) "Open file ${msg.attachment?.name.orEmpty()}" else "File ${msg.attachment?.name.orEmpty()}, not available" }
                                .padding(vertical = 4.dp)
                                .padding(bottom = 3.dp),
                        )
                        MessageKind.TEXT -> Unit
                    }
                    if (msg.body.isNotEmpty()) {
                        // styled once per body/range change; a tap inside a link opens it, a tap
                        // anywhere else still falls through to the bubble's own click handler
                        val body = remember(msg.body, linkRanges, ink) {
                            if (linkRanges.isEmpty()) {
                                AnnotatedString(msg.body)
                            } else {
                                buildAnnotatedString {
                                    var at = 0
                                    for (range in linkRanges) {
                                        if (range.first > at) append(msg.body.substring(at, range.first))
                                        val url = msg.body.substring(range.first, range.last + 1)
                                        val styles = TextLinkStyles(SpanStyle(color = ink, textDecoration = TextDecoration.Underline))
                                        withLink(LinkAnnotation.Url(url, styles) { onClickLink(url) }) { append(url) }
                                        at = range.last + 1
                                    }
                                    if (at < msg.body.length) append(msg.body.substring(at))
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(body, style = TnType.body.copy(fontSize = textSizeSp.sp, lineHeight = (textSizeSp + 5.5f).sp), color = ink, modifier = Modifier.weight(1f, fill = false))
                            if (msg.isEdited) Icon(TnIcon.PENCIL, tint = inkMuted, size = 11.dp, contentDescription = "Edited", modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }
            }

            // one pill for every reaction, overlapping the bubble's bottom edge
            if (reactionCount > 0) {
                val emojis = remember(msg.reactions) { msg.reactions.values.toSet().joinToString("") }
                Text(
                    if (reactionCount > 1) "$emojis $reactionCount" else emojis,
                    style = TnType.caption.copy(fontSize = 12.5.sp),
                    color = colors.text,
                    modifier = Modifier
                        .offset(y = (-6).dp)
                        .padding(horizontal = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.background)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceRaised)
                        .combinedClickable(role = Role.Button, onClick = { onClickReactions(msg) })
                        .semantics { contentDescription = "Reactions $emojis, $reactionCount. Tap to see who reacted" }
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }

            BubbleDetails(
                msg = msg,
                actions = actions,
                live = live,
                runningId = runningId,
                receivingPct = receivingPct,
                isExpanded = isExpanded,
                isMine = mine,
                isPeerSending = isPeerSending,
                modifier = Modifier.widthIn(max = maxBubbleWidth()).padding(top = 3.dp, start = if (mine) 0.dp else 6.dp, end = if (mine) 6.dp else 0.dp),
            )
            if (showSeenAvatar) {
                Box(modifier = Modifier.padding(top = 3.dp, end = 4.dp).semantics { contentDescription = "Seen by $peerName" }) { Avatar(peerName, size = 16.dp) }
            }
        }
    }
}

/** What is still happening to the message; tapped open it also lists every action and the times. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BubbleDetails(
    msg: ChatMessage,
    actions: List<MessageAction>,
    live: LiveSummary?,
    runningId: String?,
    receivingPct: Float?,
    isExpanded: Boolean,
    isMine: Boolean,
    isPeerSending: Boolean,
    modifier: Modifier,
) {
    if (!isExpanded && live == null && receivingPct == null) return
    val colors = TnTheme.colors
    val style = TnType.caption.copy(fontSize = 11.sp)
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (isExpanded) {
            if (actions.size > MAX_INDICATORS) Text("…", style = style, color = colors.textMuted)
            for (a in actions.takeLast(MAX_INDICATORS)) ActionIndicator(a, a.id == runningId, isPeerSending)
        } else if (live != null) {
            for (a in actions) if (a.status in UNSETTLED) ActionIndicator(a, a.id == runningId, isPeerSending)
        }
        if (live != null) Text(live.text, style = style, color = if (live.hasFailure) colors.danger else colors.textMuted)
        if (receivingPct != null) ReceivingProgress(receivingPct, style)
        if (isExpanded) {
            msg.seenAt?.takeIf { isMine }?.let { Text("seen ${Format.clock(it)}", style = style, color = colors.textMuted) }
            msg.expireSecs?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(TnIcon.CLOCK, tint = colors.textMuted, size = 11.dp)
                    Text(Format.seconds(it.toInt()), style = style, color = colors.textMuted)
                }
            }
            Text(Format.clock(msg.ts), style = style, color = colors.textMuted)
        }
    }
}

@Composable
private fun maxBubbleWidth(): androidx.compose.ui.unit.Dp {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    return (configuration.screenWidthDp * 0.82f).dp
}

/** A voice clip: play or pause, a position bar, and the length before anything is played. */
@Composable
private fun VoiceClip(
    attachment: MessageAttachment,
    playback: VoicePlayback?,
    canPlay: Boolean,
    ink: Color,
    inkMuted: Color,
    inkSurface: Color,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
) {
    val durationMs = playback?.durationMs?.takeIf { it > 0 } ?: attachment.durationMs ?: 0L
    val positionMs = playback?.positionMs ?: 0L
    val isPlaying = playback?.isPlaying == true
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .combinedClickable(enabled = canPlay, role = Role.Button, onClick = onToggle, onLongClick = onLongClick)
            .semantics {
                contentDescription = when {
                    !canPlay -> "Voice message, not on this device"
                    isPlaying -> "Pause voice message"
                    else -> "Play voice message"
                }
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(inkSurface), contentAlignment = Alignment.Center) {
            Icon(if (isPlaying) TnIcon.PAUSE else TnIcon.PLAY, tint = if (canPlay) ink else inkMuted, size = 18.dp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(inkSurface)) {
                Box(modifier = Modifier.fillMaxWidth(fraction).height(4.dp).clip(CircleShape).background(ink))
            }
            Text(
                when {
                    !canPlay -> "Voice message · not on this device"
                    positionMs > 0 || isPlaying -> "${Format.clockMs(positionMs)} / ${Format.clockMs(durationMs)}"
                    durationMs > 0 -> Format.clockMs(durationMs)
                    else -> "Voice message"
                },
                style = TnType.caption,
                color = inkMuted,
            )
        }
    }
}

@Composable
private fun CoverFace(ink: Color, inkMuted: Color, inkSurface: Color, textSizeSp: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(inkSurface),
            contentAlignment = Alignment.Center,
        ) { Icon(TnIcon.LOCK, tint = ink, size = 16.dp) }
        Column {
            Text("Covered message", style = TnType.body.copy(fontSize = textSizeSp.sp), color = ink)
            Text("Tap to reveal", style = TnType.caption, color = inkMuted)
        }
    }
}

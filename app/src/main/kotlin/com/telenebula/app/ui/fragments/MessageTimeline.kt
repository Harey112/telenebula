package com.telenebula.app.ui.fragments

import com.telenebula.app.platform.VoicePlayback
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import com.telenebula.app.ui.theme.TnTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.MessageAction
import java.time.LocalDate

/** One row of the inverted timeline; keys are stable across re-queries. */
sealed interface TimelineItem {
    val key: String

    class Msg(val msg: ChatMessage) : TimelineItem {
        override val key: String get() = msg.id
    }

    class Date(val ts: Long, day: LocalDate) : TimelineItem {
        override val key: String = "d-$day"
    }

    /** Sits above the first message that was still unread when the chat was opened. */
    object Unread : TimelineItem {
        override val key: String = "unread"
    }
}

/**
 * The inverted list of bubbles and date pills: item 0 is the newest and sits at the bottom.
 *
 * A LazyColumn re-anchors on the row that was first visible, so rows added at index 0 land
 * *below* the viewport — a new message would stay hidden behind the composer until the user
 * scrolled. Whenever the newest row changes, this follows it back down, but only if the reader
 * was already at the bottom: a reader scrolled up in the history is never yanked away.
 */
@Composable
fun MessageTimeline(
    items: List<TimelineItem>,
    actionsByMessage: Map<String, List<MessageAction>>,
    replyPreviews: Map<String, ReplyPreview>,
    linkRanges: Map<String, List<IntRange>>,
    transferProgress: Map<String, Double>,
    /** messages whose cover has been lifted for as long as this chat stays open */
    revealedIds: Set<String>,
    /** incoming transfers this device cancelled itself */
    cancelledByMe: Set<String>,
    textSizeSp: Float,
    isCompact: Boolean,
    expandedMessageId: String?,
    seenAvatarMessageId: String?,
    peerName: String,
    /** a worker is on this peer right now, so queued work is actually moving */
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
    freeBytes: Long,
    modifier: Modifier = Modifier,
    hasOlder: Boolean = false,
    isLoadingOlder: Boolean = false,
    onReachOlder: () -> Unit = {},
    onReachNewest: () -> Unit = {},
    voicePlayback: VoicePlayback? = null,
    onToggleVoice: (ChatMessage) -> Unit = {},
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState, hasOlder) {
        if (!hasOlder) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - OLDER_PREFETCH_ROWS
        }.distinctUntilChanged().filter { it }.collect { onReachOlder() }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            .distinctUntilChanged().filter { it }.collect { onReachNewest() }
    }
    val newestKey = items.firstOrNull()?.key
    var followedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(newestKey) {
        val previous = followedKey
        followedKey = newestKey
        if (newestKey == null || newestKey == previous) return@LaunchedEffect
        // how many rows were added on top of the one we were following; the effect can run either
        // side of the re-measure, so the pre-shift index (0) and the post-shift one both pass here
        val added = previous?.let { key -> items.indexOfFirst { it.key == key }.coerceAtLeast(0) } ?: 0
        if (listState.firstVisibleItemIndex <= added) listState.animateScrollToItem(0)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = TnSpace.sm + 2.dp, vertical = TnSpace.sm),
    ) {
        items(items, key = { it.key }, contentType = { if (it is TimelineItem.Msg) "msg" else "separator" }) { item ->
            when (item) {
                is TimelineItem.Date -> DateSeparator(item.ts)
                is TimelineItem.Unread -> UnreadSeparator()
                is TimelineItem.Msg -> MessageBubble(
                    msg = item.msg,
                    actions = actionsByMessage[item.msg.id].orEmpty(),
                    textSizeSp = textSizeSp,
                    isCompact = isCompact,
                    repliedPreview = replyPreviews[item.msg.id],
                    linkRanges = linkRanges[item.msg.id].orEmpty(),
                    transferPct = transferProgress[item.msg.id]?.toFloat(),
                    isCovered = item.msg.isCovered && !item.msg.isDeleted && item.msg.id !in revealedIds,
                    isCancelledByMe = item.msg.id in cancelledByMe,
                    isExpanded = expandedMessageId == item.msg.id,
                    showSeenAvatar = seenAvatarMessageId == item.msg.id,
                    peerName = peerName,
                    isPeerSending = isPeerSending,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onReply = onReply,
                    onClickAttachment = onClickAttachment,
                    onCancelTransfer = onCancelTransfer,
                    onClickReactions = onClickReactions,
                    onClickLink = onClickLink,
                    onRetrySend = onRetrySend,
                    onAcceptOffer = onAcceptOffer,
                    onDeclineOffer = onDeclineOffer,
                    freeBytes = freeBytes,
                    voicePlayback = voicePlayback?.takeIf { it.messageId == item.msg.id },
                    onToggleVoice = onToggleVoice,
                )
            }
        }
        if (isLoadingOlder) {
            item(key = "older-loading", contentType = "separator") {
                Box(modifier = Modifier.fillMaxWidth().padding(TnSpace.md), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = TnTheme.colors.accent, trackColor = TnTheme.colors.hairline, strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** rows from the old end at which the next page is asked for */
private const val OLDER_PREFETCH_ROWS = 20

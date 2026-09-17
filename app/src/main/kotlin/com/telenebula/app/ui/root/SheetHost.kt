package com.telenebula.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.AppGraph
import com.telenebula.app.LocalAppGraph
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.sheets.SheetRequest
import com.telenebula.app.ui.fragments.ActionsList
import com.telenebula.app.ui.fragments.EmojiPicker
import com.telenebula.app.ui.fragments.ReactionEntry
import com.telenebula.app.ui.fragments.ReactionsList
import com.telenebula.app.ui.fragments.TnBottomSheet
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Root bottom sheet, mounted once. A request names what to show; each sheet reads its own data
 * from the graph so it keeps updating while the list underneath re-queries, and closes itself
 * when the message goes away.
 */
@Composable
fun SheetHost() {
    val graph = LocalAppGraph.current
    val request by graph.sheets.request.collectAsStateWithLifecycle()
    val current = request
    TnBottomSheet(isVisible = current != null, title = current?.title.orEmpty(), onClose = graph.sheets::close) {
        when (current) {
            is SheetRequest.MessageActivity -> MessageActivitySheet(current, graph)
            is SheetRequest.Reactions -> ReactionsSheet(current, graph)
            is SheetRequest.ReactionPicker -> ReactionPickerSheet(current, graph)
            is SheetRequest.QuickReaction -> QuickReactionSheet(current, graph)
            null -> Unit
        }
    }
}

@Composable
private fun MessageActivitySheet(request: SheetRequest.MessageActivity, graph: AppGraph) {
    val message by graph.core.messageFlow(request.messageId, request.peerIp).collectAsStateWithLifecycle(initialValue = LOADING)
    val actions by graph.core.messageActionsFlow(request.messageId, request.peerIp).collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(message) { if (message == null) graph.sheets.close() }
    val queue by graph.peerQueues.queues.collectAsStateWithLifecycle()
    ActionsList(
        actions = actions,
        // nothing queued for this peer means nothing is waiting on it, so it reads as reachable
        isPeerReachable = queue[request.peerIp]?.isReachable ?: true,
        onRetry = { id -> graph.appScope.launch { graph.core.retryAction(id) } },
        onCancel = { id -> graph.appScope.launch { graph.core.cancelAction(id) } },
    )
}

@Composable
private fun ReactionsSheet(request: SheetRequest.Reactions, graph: AppGraph) {
    val profile by graph.runtime.profile.collectAsStateWithLifecycle()
    val myIp = profile?.overlayIp.orEmpty()
    val entries by remember(request, myIp) {
        combine(graph.core.messageFlow(request.messageId, request.peerIp), graph.core.contactFlow(request.peerIp)) { message, contact ->
            val peerName = contact?.let(ContactLabels::chatLabel) ?: request.peerIp
            message?.reactions?.map { (ip, emoji) -> ReactionEntry(ip, emoji, if (ip == myIp) "You" else peerName, isMine = ip == myIp) }
        }
    }.collectAsStateWithLifecycle(initialValue = INITIAL_ENTRIES)
    val current = entries
    // nothing left to show: the message went away or every reaction was removed
    LaunchedEffect(current) { if (current !== INITIAL_ENTRIES && current.isNullOrEmpty()) graph.sheets.close() }
    ReactionsList(
        entries = current.orEmpty(),
        onRemove = {
            current?.firstOrNull { it.isMine }?.let { mine -> graph.appScope.launch { graph.core.reactToMessage(request.messageId, mine.emoji) } }
            graph.sheets.close()
        },
    )
}

@Composable
private fun ReactionPickerSheet(request: SheetRequest.ReactionPicker, graph: AppGraph) {
    val profile by graph.runtime.profile.collectAsStateWithLifecycle()
    val myIp = profile?.overlayIp.orEmpty()
    val p by graph.prefs.prefs.collectAsStateWithLifecycle()
    val message by graph.core.messageFlow(request.messageId, request.peerIp).collectAsStateWithLifecycle(initialValue = LOADING)
    val recent = remember(p.recentReactions, p.quickReactions) { p.recentReactions.filterNot { it in p.quickReactions } }
    val groups by graph.emojis.groups.collectAsStateWithLifecycle()
    EmojiPicker(
        groups = groups,
        yourReactions = p.quickReactions,
        recentReactions = recent,
        currentReaction = message?.takeIf { it !== LOADING }?.reactions?.get(myIp),
        onPick = { emoji ->
            graph.appScope.launch { graph.core.reactToMessage(request.messageId, emoji) }
            graph.prefs.recordRecentReaction(emoji)
            graph.sheets.close()
        },
    )
}

@Composable
private fun QuickReactionSheet(request: SheetRequest.QuickReaction, graph: AppGraph) {
    val p by graph.prefs.prefs.collectAsStateWithLifecycle()
    val groups by graph.emojis.groups.collectAsStateWithLifecycle()
    EmojiPicker(
        groups = groups,
        yourReactions = emptyList(),
        recentReactions = emptyList(),
        currentReaction = p.quickReactions.getOrNull(request.slot),
        onPick = { emoji ->
            graph.prefs.setQuickReaction(request.slot, emoji)
            graph.sheets.close()
        },
    )
}

/** distinguishes "not loaded yet" from "deleted" so a sheet never closes on its first frame */
private val LOADING = ChatMessage(id = "", peerIp = "", direction = MessageDirection.IN, body = "", ts = 0, status = MessageStatus.RECEIVED, kind = MessageKind.TEXT)
private val INITIAL_ENTRIES: List<ReactionEntry>? = ArrayList(0)

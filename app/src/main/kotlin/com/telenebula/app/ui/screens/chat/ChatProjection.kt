package com.telenebula.app.ui.screens.chat

import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.platform.Format
import com.telenebula.app.platform.LinkSpans
import com.telenebula.app.ui.fragments.ReplyPreview
import com.telenebula.app.ui.fragments.TimelineItem
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.ChatView
import com.telenebula.core.model.Contact
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import com.telenebula.core.model.MessageDirection
import java.time.LocalDate

/** Everything one ChatView says about the timeline, computed once per query. */
class ChatDerived(
    val view: ChatView,
    val isLoaded: Boolean,
    val items: List<TimelineItem>,
    val replyPreviews: Map<String, ReplyPreview>,
    val linkRanges: Map<String, List<IntRange>>,
    val seenAvatarMessageId: String?,
    val byId: Map<String, ChatMessage>,
)

/** A pure function of the view and the pinned unread divider; it keeps no state of its own. */
class ChatProjection(private val peerIp: String, private val cachedContact: (String) -> Contact?) {
    /** A null view is a chat not read yet: the header still gets the cached contact's name. */
    fun of(loaded: ChatView?, unreadBoundaryId: String?): ChatDerived {
        val view = loaded ?: ChatView(contact = cachedContact(peerIp))
        val messages = view.messages
        val contact = view.contact
        // a message whose send gave up has no settled place in the conversation: it is held at the
        // very end, below every dated message, until a retry succeeds and it rejoins its own day
        val (placed, unsent) = messages.partition { !hasFailedSend(view.actions[it.id]) }
        val items = ArrayList<TimelineItem>(messages.size + 8)
        val links = HashMap<String, List<IntRange>>()
        var lastDay: LocalDate? = null
        for (msg in placed) {
            val day = Format.dayOf(msg.ts)
            if (day != lastDay) {
                items.add(TimelineItem.Date(msg.ts, day))
                lastDay = day
            }
            if (msg.id == unreadBoundaryId) items.add(TimelineItem.Unread)
            items.add(TimelineItem.Msg(msg))
        }
        // no date separator for these: their position is the point, and their own time is in details
        for (msg in unsent) items.add(TimelineItem.Msg(msg))
        for (msg in messages) {
            // scanned once per query, not per recomposition: the bubble only styles the ranges
            if (!msg.isDeleted) {
                val ranges = LinkSpans.urlRangesIn(msg.body)
                if (ranges.isNotEmpty()) links[msg.id] = ranges
            }
        }
        items.reverse()
        val previews = HashMap<String, ReplyPreview>()
        for (msg in messages) {
            val replyTo = msg.replyToId ?: continue
            val replied = view.replySources[replyTo] ?: continue
            val name = if (replied.direction == MessageDirection.OUT) "You" else contact?.let(ContactLabels::chatLabel) ?: replied.peerIp
            val cover = replied.cover?.takeIf { it.isNotBlank() }
            val snippet = when {
                replied.isDeleted -> "Message deleted"
                cover != null -> cover
                else -> replied.body.ifEmpty { replied.attachment?.name ?: "Attachment" }
            }
            previews[msg.id] = ReplyPreview(name, snippet)
        }
        // the peer's avatar marks the last message they saw, only while they have not replied since
        var seenId: String? = null
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            if (m.direction == MessageDirection.IN) break
            if (m.seenAt != null && !m.isDeleted) {
                seenId = m.id
                break
            }
        }
        val byId = HashMap<String, ChatMessage>(messages.size * 2)
        for (m in messages) byId[m.id] = m
        return ChatDerived(view, loaded != null, items, previews, links, seenId, byId)
    }

    private fun hasFailedSend(actions: List<MessageAction>?): Boolean =
        actions?.any { it.type == MessageActionType.SEND && it.status == MessageActionStatus.FAILED } == true

    companion object {
        fun firstUnreadId(view: ChatView): String? = view.messages.firstOrNull { it.direction == MessageDirection.IN && !it.isRead }?.id
    }
}

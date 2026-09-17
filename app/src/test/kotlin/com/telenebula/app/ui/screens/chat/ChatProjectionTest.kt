package com.telenebula.app.ui.screens.chat

import com.telenebula.app.ui.fragments.TimelineItem
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.ChatView
import com.telenebula.core.model.Contact
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProjectionTest {
    private val peer = "fd::2"
    private val contact = Contact(ip = peer, name = "bob", nickname = "Bobby", addedAt = 0)
    private val projection = ChatProjection(peer) { contact }

    private fun noon(daysAgo: Long): Long = LocalDate.now().minusDays(daysAgo).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun msg(id: String, ts: Long, direction: MessageDirection = MessageDirection.IN, body: String = "m $id", isRead: Boolean = true, replyTo: String? = null, seenAt: Long? = null) =
        ChatMessage(id = id, peerIp = peer, direction = direction, body = body, ts = ts, status = MessageStatus.RECEIVED, kind = MessageKind.TEXT, isRead = isRead, replyToId = replyTo, seenAt = seenAt)

    @Test
    fun `a chat not read yet still has the cached contact`() {
        val d = projection.of(null, null)
        assertFalse(d.isLoaded)
        assertEquals(contact, d.view.contact)
        assertTrue(d.items.isEmpty())
    }

    @Test
    fun `messages are grouped under date pills, newest first, with the unread divider pinned`() {
        val a = msg("a", noon(1))
        val b = msg("b", noon(0), isRead = false)
        val c = msg("c", noon(0) + 1_000, isRead = false)
        val d = projection.of(ChatView(contact = contact, messages = listOf(a, b, c)), unreadBoundaryId = "b")
        assertTrue(d.isLoaded)
        assertEquals(listOf("c", "b", "unread", "d-${LocalDate.now()}", "a", "d-${LocalDate.now().minusDays(1)}"), d.items.map { it.key })
    }

    @Test
    fun `a message whose send failed is held at the very end without a date pill`() {
        val old = msg("old", noon(3), direction = MessageDirection.OUT)
        val fresh = msg("fresh", noon(0))
        val failed = MessageAction(id = "old", messageId = "old", peerIp = peer, type = MessageActionType.SEND, status = MessageActionStatus.FAILED, createdAt = 0, updatedAt = 0)
        val d = projection.of(ChatView(contact = contact, messages = listOf(old, fresh), actions = mapOf("old" to listOf(failed))), null)
        assertEquals("old", d.items.first().key)
        assertEquals(listOf("old", "fresh", "d-${LocalDate.now()}"), d.items.map { it.key })
    }

    @Test
    fun `reply previews, link ranges and the seen avatar are computed once per view`() {
        val quoted = msg("q", noon(2), body = "see https://example.org today")
        val reply = msg("r", noon(0), direction = MessageDirection.OUT, body = "ok", replyTo = "q", seenAt = 5)
        val d = projection.of(ChatView(contact = contact, messages = listOf(quoted, reply), replySources = mapOf("q" to quoted)), null)
        assertEquals("Bobby", d.replyPreviews.getValue("r").name)
        assertEquals(listOf(4..22), d.linkRanges.getValue("q"))
        assertNull(d.linkRanges["r"])
        assertEquals("r", d.seenAvatarMessageId)
        assertEquals(setOf("q", "r"), d.byId.keys)
        assertEquals(2, d.items.count { it is TimelineItem.Msg })
    }

    @Test
    fun `the first unread incoming message is the boundary`() {
        val view = ChatView(messages = listOf(msg("a", 1), msg("b", 2, isRead = false), msg("c", 3, isRead = false)))
        assertEquals("b", ChatProjection.firstUnreadId(view))
        assertNull(ChatProjection.firstUnreadId(ChatView(messages = listOf(msg("a", 1)))))
    }
}

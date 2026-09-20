package com.telenebula.core.engine

import com.telenebula.core.CorePaths
import com.telenebula.core.db.JdbcSqlDb
import com.telenebula.core.db.Schema
import com.telenebula.core.db.Store
import com.telenebula.core.db.Wire
import com.telenebula.core.events.CoreEvent
import com.telenebula.core.model.ContactPrivacyPrefs
import com.telenebula.core.model.PeerPresence
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import com.telenebula.core.model.MessageAttachment
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.EnvelopeType
import com.telenebula.core.model.MessageStatus
import java.io.File
import java.util.Base64
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two complete engines on the loopback interface, talking the real wire protocol to each other:
 * framing, the ack registry, the outbox's attempt and retry rules, the transfer state machine and
 * the event pump, all at once. The two nodes see each other as ordinary peers — each binds its own
 * loopback address, so nothing here knows it is a test.
 */
class EngineLoopbackTest {
    private val scratch = File(System.getProperty("java.io.tmpdir"), "tn-engine-${System.nanoTime()}")
    private val nodes = ArrayList<Node>()

    private inner class Node(val ip: String, val name: String, val port: Int) {
        val events = CopyOnWriteArrayList<CoreEvent>()
        val attachments = File(scratch, "$name/attachments").apply { mkdirs() }
        val store: Store = run {
            val path = File(scratch, "$name/chats.db").apply { parentFile?.mkdirs() }.path
            val db = JdbcSqlDb.open(path)
            Schema.apply(db)
            Store(db, path, JdbcSqlDb::open)
        }
        /** replaced across a restart; the store under it is not, which is the point */
        var engine = newEngine()
            private set

        private fun newEngine(isTunnelUp: Boolean = true) = Engine(
            store = store,
            profile = EngineProfile(
                overlayIp = ip,
                displayName = name,
                msgPort = port,
                appVersion = "test",
                bindHost = ip,
            ),
            attachmentsDir = attachments,
            sendReadReceipts = true,
            sink = { events += it },
            isTunnelUp = isTunnelUp,
        ).also { it.start() }

        /** The device goes away. Its queue stays on disk, which is what makes it a queue. */
        fun down() = engine.stop()

        /** …and comes back, on the same address and the same database. */
        fun up(isTunnelUp: Boolean = true) {
            engine = newEngine(isTunnelUp)
        }

        fun stop() {
            engine.stop()
            store.close()
        }
    }

    @After
    fun tearDown() {
        nodes.forEach { runCatching { it.stop() } }
        scratch.deleteRecursively()
    }

    private fun third(a: Node): Node = Node("127.0.0.3", "carol", a.port).also { nodes += it }

    /** An offer the receiver has not answered yet, with the sender parked on it. */
    private suspend fun offered(a: Node, b: Node, name: String): String {
        val bytes = ByteArray((Limits.ATT_OFFER_THRESHOLD_BYTES + 1024).toInt())
        val source = File(scratch, "alice/attachments/$name").apply { writeBytes(bytes) }
        a.engine.outbox.sendAttachment(
            b.ip,
            source.path,
            MessageAttachment(name = name, mime = "application/octet-stream", size = bytes.size.toLong()),
            null,
        )
        waitFor("the offer to arrive") { b.store.getMessages(a.ip, 10).firstOrNull()?.status == MessageStatus.OFFERED }
        val id = b.store.getMessages(a.ip, 10).single().id
        waitFor("the sender to park on the offer") { a.store.getAction(id)?.status == MessageActionStatus.WAITING }
        return id
    }

    private fun pair(): Pair<Node, Node> {
        // both nodes listen on the same port, each on its own loopback address, exactly as two
        // devices share the fixed message port on the overlay
        val port = ServerSocket(0).use { it.localPort }
        val a = Node("127.0.0.1", "alice", port)
        val b = Node("127.0.0.2", "bob", port)
        nodes += a
        nodes += b
        return a to b
    }

    /** Headroom demanded of the next scheduled probe, so no timer can drain the queue on its own. */
    private val PROBE_HEADROOM_MS = 10_000L

    private suspend fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** Accepted state without bytes in flight: a real accept races the stream and often loses. */
    private suspend fun offerAccepted(a: Node, b: Node, name: String): String {
        val bytes = ByteArray((Limits.ATT_OFFER_THRESHOLD_BYTES + 1024).toInt())
        val source = File(scratch, "alice/attachments/$name").apply { writeBytes(bytes) }
        a.engine.outbox.sendAttachment(
            b.ip,
            source.path,
            MessageAttachment(name = name, mime = "application/octet-stream", size = bytes.size.toLong()),
            null,
        )
        waitFor("the offer to arrive") { b.store.getMessages(a.ip, 10).firstOrNull()?.status == MessageStatus.OFFERED }
        waitFor("the sender to park on the offer") {
            a.store.getAction(b.store.getMessages(a.ip, 10).single().id)?.status == MessageActionStatus.WAITING
        }
        val id = b.store.getMessages(a.ip, 10).single().id
        b.store.setTransferState(id, Wire.TransferState.RECEIVING)
        b.store.setMessageStatus(id, MessageStatus.RECEIVING)
        a.store.setTransferState(id, Wire.TransferState.ACCEPTED)
        return id
    }

    @Test
    fun `nothing is probed while the tunnel is down, and the queue moves when it returns`() = runBlocking {
        val (a, b) = pair()
        a.down()
        a.up(isTunnelUp = false)
        a.engine.outbox.sendText(b.ip, "queued in the dark", null)
        val id = a.store.getMessages(b.ip, 10).single().id

        val startedAt = System.currentTimeMillis()
        assertEquals(Transport.UNREACHABLE, a.engine.pingPeer(b.ip, 5_000))
        assertTrue("a manual ping must not wait on a tunnel that is not there", System.currentTimeMillis() - startedAt < 1_000)

        delay(1_500)
        assertTrue(b.store.getMessages(a.ip, 10).isEmpty())
        assertEquals(MessageActionStatus.PENDING, a.store.getActionsForMessage(id).single().status)

        a.engine.onTunnelState(true)
        waitFor("the queue to drain once the tunnel is back") { b.store.getMessages(a.ip, 10).isNotEmpty() }
        waitFor("the send to be acked") { a.store.getActionsForMessage(id).single().status == MessageActionStatus.SUCCESS }
    }

    @Test
    fun `an attachment's audio length travels with it`() = runBlocking {
        val (a, b) = pair()
        val source = File(scratch, "alice/attachments/clip.m4a").apply { writeBytes(ByteArray(2_048)) }
        a.engine.outbox.sendAttachment(b.ip, source.path, MessageAttachment(name = "Voice message.m4a", mime = "audio/mp4", size = 2_048, durationMs = 4_200), null)
        waitFor("the clip to land") { b.store.getMessages(a.ip, 10).firstOrNull()?.attachment?.uri != null }
        val got = b.store.getMessages(a.ip, 10).single().attachment
        assertEquals(4_200L, got?.durationMs)
        assertEquals("audio/mp4", got?.mime)
    }

    @Test
    fun `a cover travels with a message and with an attachment`() = runBlocking {
        val (a, b) = pair()
        a.engine.outbox.sendText(b.ip, "the real thing", null, true)
        waitFor("the covered message to arrive") { b.store.getMessages(a.ip, 10).isNotEmpty() }
        val text = b.store.getMessages(a.ip, 10).single()
        assertTrue(text.isCovered)
        assertEquals("the real thing", text.body)

        val source = File(scratch, "alice/attachments/covered.m4a").apply { writeBytes(ByteArray(2_048)) }
        a.engine.outbox.sendAttachment(
            b.ip,
            source.path,
            MessageAttachment(name = "Voice message.m4a", mime = "audio/mp4", size = 2_048, durationMs = 4_200),
            null,
            true,
        )
        waitFor("the covered clip to land") { b.store.getMessages(a.ip, 10).size == 2 && b.store.getMessages(a.ip, 10).last().attachment?.uri != null }
        assertTrue(b.store.getMessages(a.ip, 10).last().isCovered)
    }

    @Test
    fun `a reply to a peer that just wrote is still probed before it goes`() = runBlocking {
        val (a, b) = pair()
        b.engine.outbox.sendText(a.ip, "are you there", null)
        waitFor("their message to arrive") { a.store.getMessages(b.ip, 10).isNotEmpty() }

        // hearing from them is not a round trip of ours: the reply leaves only once our probe answers
        a.engine.outbox.sendText(b.ip, "yes", null)
        waitFor("the reply to land") { b.store.getMessages(a.ip, 10).any { it.body == "yes" } }
    }

    @Test
    fun `an offer is announced by its file, and a landed clip by what it is`() = runBlocking {
        val (a, b) = pair()
        offered(a, b, "holiday.bin")
        waitFor("the offer to be announced") { b.events.any { it is CoreEvent.MessageReceived && "holiday.bin" in it.preview } }

        val source = File(scratch, "alice/attachments/note.m4a").apply { writeBytes(ByteArray(2_048)) }
        a.engine.outbox.sendAttachment(b.ip, source.path, MessageAttachment(name = "Voice message.m4a", mime = "audio/mp4", size = 2_048, durationMs = 1_500), null)
        waitFor("the clip to be announced as a voice message") { b.events.any { it is CoreEvent.MessageReceived && it.preview == "Voice message" } }
    }

    @Test
    fun `the receiver's answer to a file reaches the sender as an outcome`() = runBlocking {
        val (a, b) = pair()
        val id = offered(a, b, "declined.bin")
        b.engine.attachments.answerOffer(id, accept = false, freeBytes = 0)
        waitFor("the sender to be told") {
            a.events.any { it is CoreEvent.TransferOutcome && it.fileName == "declined.bin" && it.summary == "Declined your file" }
        }
    }

    @Test
    fun `a text message is delivered, acked and announced`() = runBlocking {
        val (a, b) = pair()
        a.engine.outbox.sendText(b.ip, "hello over the overlay", null)

        waitFor("the message to arrive") { b.store.getMessages(a.ip, 10).isNotEmpty() }
        val received = b.store.getMessages(a.ip, 10).single()
        assertEquals("hello over the overlay", received.body)
        assertEquals(MessageDirection.IN, received.direction)
        assertEquals(MessageStatus.RECEIVED, received.status)
        assertEquals(false, received.isRead)

        waitFor("the send to be acked") {
            a.store.getActionsForMessage(received.id).singleOrNull()?.status == MessageActionStatus.SUCCESS
        }
        assertEquals(MessageStatus.DELIVERED, a.store.getMessage(received.id)?.status)

        // the peer announced itself on the hello, so the contact exists on both sides
        assertEquals("alice", b.store.getContact(a.ip)?.name)
        waitFor("bob to be notified") { b.events.any { it is CoreEvent.MessageReceived } }
        val notice = b.events.filterIsInstance<CoreEvent.MessageReceived>().first()
        assertEquals("alice", notice.name)
        assertEquals("hello over the overlay", notice.preview)
    }

    @Test
    fun `reading a chat reports the messages as seen`() = runBlocking {
        val (a, b) = pair()
        a.engine.outbox.sendText(b.ip, "did you see this", null)
        waitFor("the message to arrive") { b.store.getMessages(a.ip, 10).isNotEmpty() }
        val id = b.store.getMessages(a.ip, 10).single().id
        // past the flush that carried the send itself, or its structural announcement swallows the receipt's ids
        delay(Limits.EVENT_FLUSH_MS * 3)
        a.events.clear()

        b.store.markChatRead(a.ip)
        b.engine.outbox.reportSeen(a.ip)

        // the row, not the stamp: only the ack coming back settles it here
        waitFor("the receipt to be settled") {
            b.store.getActionsForMessage(id).singleOrNull { it.type == MessageActionType.SEEN }
                ?.status == MessageActionStatus.SUCCESS
        }
        assertNotNull(a.store.getMessage(id)?.seenAt)
        waitFor("the receipt to name its row rather than the whole chat") {
            a.events.any { it is CoreEvent.ChatChanged && it.ip == b.ip && it.messageIds?.contains(id) == true }
        }
    }

    @Test
    fun `a pong carries the peer's presence and an unanswered probe means offline`() = runBlocking {
        val (a, b) = pair()
        b.engine.isOnline.set(true)
        assertTrue(a.engine.pingPeer(b.ip, null) >= 0)
        waitFor("online to be announced") { a.events.any { it is CoreEvent.PresenceChanged && it.ip == b.ip && it.presence == PeerPresence.ONLINE } }

        b.engine.isOnline.set(false)
        a.events.clear()
        assertTrue(a.engine.pingPeer(b.ip, null) >= 0)
        waitFor("reachable to be announced") { a.events.any { it is CoreEvent.PresenceChanged && it.presence == PeerPresence.REACHABLE } }

        b.down()
        a.events.clear()
        assertTrue(a.engine.pingPeer(b.ip, 2_000) < 0)
        waitFor("offline to be announced") { a.events.any { it is CoreEvent.PresenceChanged && it.presence == PeerPresence.OFFLINE } }
    }

    @Test
    fun `a per-contact receipt setting overrides the global one`() = runBlocking {
        val (a, b) = pair()
        a.engine.outbox.sendText(b.ip, "first", null)
        waitFor("the message to arrive") { b.store.getMessages(a.ip, 10).isNotEmpty() }
        val first = b.store.getMessages(a.ip, 10).single().id

        b.store.setContactPrivacy(a.ip, ContactPrivacyPrefs(sendReadReceipts = false))
        b.store.markChatRead(a.ip)
        b.engine.outbox.reportSeen(a.ip)
        assertTrue(b.store.getActionsForMessage(first).none { it.type == MessageActionType.SEEN })
        assertTrue(b.store.unreportedSeenIds(a.ip, 10).isEmpty())
        delay(500)
        assertNull(a.store.getMessage(first)?.seenAt)

        b.engine.sendReadReceipts.set(false)
        b.store.setContactPrivacy(a.ip, ContactPrivacyPrefs(sendReadReceipts = true))
        a.engine.outbox.sendText(b.ip, "second", null)
        waitFor("the second message to arrive") { b.store.getMessages(a.ip, 10).size == 2 }
        val second = b.store.getMessages(a.ip, 10).first { it.id != first }.id
        b.store.markChatRead(a.ip)
        b.engine.outbox.reportSeen(a.ip)
        waitFor("the receipt the contact setting allows") { a.store.getMessage(second)?.seenAt != null }
    }

    @Test
    fun `an edit to a read message is reported as seen again`() = runBlocking {
        val (a, b) = pair()
        a.engine.outbox.sendText(b.ip, "before", null)
        waitFor("the message to arrive") { b.store.getMessages(a.ip, 10).isNotEmpty() }
        val id = b.store.getMessages(a.ip, 10).single().id

        b.store.markChatRead(a.ip)
        b.engine.outbox.reportSeen(a.ip)
        waitFor("the first receipt to come back") { a.store.getMessage(id)?.seenAt != null }

        a.engine.outbox.editMessage(id, "after")
        waitFor("the edit to arrive") { b.store.getMessage(id)?.body == "after" }
        waitFor("a second receipt for the new content") {
            val receipts = b.store.getActionsForMessage(id).filter { it.type == MessageActionType.SEEN }
            receipts.size == 2 && receipts.all { it.status == MessageActionStatus.SUCCESS }
        }
        assertNotNull(a.store.getMessage(id)?.seenAt)
        assertTrue(b.store.getMessage(id)?.isEdited == true)
    }

    @Test
    fun `a backlog of read messages is one frame and a row each`() = runBlocking {
        val (a, b) = pair()
        repeat(5) { a.engine.outbox.sendText(b.ip, "backlog $it", null) }
        waitFor("every message to arrive") { b.store.getMessages(a.ip, 10).size == 5 }
        val ids = b.store.getMessages(a.ip, 10).map { it.id }

        b.store.markChatRead(a.ip)
        b.engine.outbox.reportSeen(a.ip)

        waitFor("every receipt to be settled") {
            ids.all { id ->
                b.store.getActionsForMessage(id).singleOrNull { it.type == MessageActionType.SEEN }
                    ?.status == MessageActionStatus.SUCCESS
            }
        }
        assertTrue("one frame carried them all", ids.all { a.store.getMessage(it)?.seenAt != null })
    }

    @Test
    fun `a reaction and an edit reach the peer in order`() = runBlocking {
        val (a, b) = pair()
        a.engine.outbox.sendText(b.ip, "first", null)
        waitFor("the message to arrive") { b.store.getMessages(a.ip, 10).isNotEmpty() }
        val id = b.store.getMessages(a.ip, 10).single().id

        b.engine.outbox.reactToMessage(id, "👍")
        waitFor("the reaction to arrive") { a.store.getMessage(id)?.reactions?.isNotEmpty() == true }
        assertEquals(mapOf(b.ip to "👍"), a.store.getMessage(id)?.reactions)

        a.engine.outbox.editMessage(id, "first, edited")
        waitFor("the edit to arrive") { b.store.getMessage(id)?.body == "first, edited" }
        assertEquals(true, b.store.getMessage(id)?.isEdited)
    }

    @Test
    fun `a small attachment streams straight through`() = runBlocking {
        val (a, b) = pair()
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val source = File(scratch, "alice/attachments/photo.bin").apply { writeBytes(bytes) }

        a.engine.outbox.sendAttachment(
            b.ip,
            source.path,
            MessageAttachment(name = "photo.bin", mime = "image/jpeg", size = bytes.size.toLong()),
            null,
        )

        waitFor("the file to land") { b.store.getMessages(a.ip, 10).firstOrNull()?.attachment?.uri != null }
        val received = b.store.getMessages(a.ip, 10).single()
        assertEquals(MessageKind.IMAGE, received.kind)
        assertEquals(MessageStatus.RECEIVED, received.status)
        val landed = File(CorePaths.uriToPath(requireNotNull(received.attachment?.uri)))
        assertTrue("the bytes survived the trip", bytes.contentEquals(landed.readBytes()))

        waitFor("the transfer to be acked") {
            a.store.getActionsForMessage(received.id).singleOrNull()?.status == MessageActionStatus.SUCCESS
        }
    }

    @Test
    fun `a large attachment is offered first and streams once accepted`() = runBlocking {
        val (a, b) = pair()
        val bytes = ByteArray((Limits.ATT_OFFER_THRESHOLD_BYTES + 1024).toInt()) { (it % 97).toByte() }
        val source = File(scratch, "alice/attachments/big.bin").apply { writeBytes(bytes) }

        a.engine.outbox.sendAttachment(
            b.ip,
            source.path,
            MessageAttachment(name = "big.bin", mime = "application/octet-stream", size = bytes.size.toLong()),
            null,
        )

        // the offer arrives as a message waiting for an answer, with no bytes moved yet
        waitFor("the offer to arrive") { b.store.getMessages(a.ip, 10).firstOrNull()?.status == MessageStatus.OFFERED }
        val offered = b.store.getMessages(a.ip, 10).single()
        assertEquals(null, offered.attachment?.uri)
        waitFor("the sender to park on the offer") {
            a.store.getAction(offered.id)?.status == MessageActionStatus.WAITING
        }

        b.engine.attachments.answerOffer(offered.id, accept = true, freeBytes = Long.MAX_VALUE / 2)

        waitFor("the file to land", timeoutMs = 30_000) {
            b.store.getMessage(offered.id)?.attachment?.uri != null
        }
        val landed = File(CorePaths.uriToPath(requireNotNull(b.store.getMessage(offered.id)?.attachment?.uri)))
        assertTrue("the bytes survived the trip", bytes.contentEquals(landed.readBytes()))
        assertEquals(MessageStatus.RECEIVED, b.store.getMessage(offered.id)?.status)
        waitFor("the transfer to be acked") {
            a.store.getAction(offered.id)?.status == MessageActionStatus.SUCCESS
        }
    }

    @Test
    fun `the receiver cancelling an accepted transfer stops the sender and clears the partial`() = runBlocking {
        val (a, b) = pair()
        val id = offerAccepted(a, b, "unwanted-midway.bin")

        b.engine.attachments.cancelIncoming(id)

        assertEquals(MessageStatus.CANCELLED, b.store.getMessage(id)?.status)
        assertEquals(0L, b.store.transferReceived(id))
        assertTrue("the receiver's own cancel is not blamed on the sender", id in b.store.getChatView(a.ip, 10).cancelledByMe)
        waitFor("the sender to hear about it") {
            a.store.getAction(id)?.status == MessageActionStatus.CANCELLED
        }
        assertEquals(TransferManager.CANCELLED, a.store.getAction(id)?.payload?.failReason)
    }

    @Test
    fun `the sender cancelling an accepted transfer tells the receiver`() = runBlocking {
        val (a, b) = pair()
        val id = offerAccepted(a, b, "withdrawn.bin")

        a.engine.outbox.cancelAction(id)

        waitFor("the receiver to be told") { b.store.getMessage(id)?.status == MessageStatus.CANCELLED }
        assertEquals(null, b.store.getMessage(id)?.attachment?.uri)
        assertEquals(MessageActionStatus.CANCELLED, a.store.getAction(id)?.status)
    }

    @Test
    fun `cancelling mid-stream stops the bytes and frees the queue behind it`() = runBlocking {
        val (a, b) = pair()
        val bytes = ByteArray(40 * 1024 * 1024) { (it % 251).toByte() }
        val source = File(scratch, "alice/attachments/huge.bin").apply { writeBytes(bytes) }

        a.engine.outbox.sendAttachment(
            b.ip,
            source.path,
            MessageAttachment(name = "huge.bin", mime = "application/octet-stream", size = bytes.size.toLong()),
            null,
        )
        waitFor("the offer to arrive") { b.store.getMessages(a.ip, 10).firstOrNull()?.status == MessageStatus.OFFERED }
        val id = b.store.getMessages(a.ip, 10).single().id
        b.engine.attachments.answerOffer(id, accept = true, freeBytes = Long.MAX_VALUE / 2)

        waitFor("the stream to be under way") { b.store.transferReceived(id) > 2 }
        b.engine.attachments.cancelIncoming(id)

        waitFor("the sender to give up on it") { a.store.getAction(id)?.status == MessageActionStatus.CANCELLED }
        val stoppedAt = b.store.transferReceived(id)
        a.engine.outbox.sendText(b.ip, "can you still hear me", null)
        waitFor("the queue behind it to move") {
            b.store.getMessages(a.ip, 20).any { it.body == "can you still hear me" }
        }
        assertEquals("no bytes after the cancel", stoppedAt, b.store.transferReceived(id))
    }

    @Test
    fun `a declined offer ends as cancelled on the sender, with the reason kept`() = runBlocking {
        val (a, b) = pair()
        val bytes = ByteArray((Limits.ATT_OFFER_THRESHOLD_BYTES + 1024).toInt())
        val source = File(scratch, "alice/attachments/unwanted.bin").apply { writeBytes(bytes) }

        a.engine.outbox.sendAttachment(
            b.ip,
            source.path,
            MessageAttachment(name = "unwanted.bin", mime = "application/octet-stream", size = bytes.size.toLong()),
            null,
        )
        waitFor("the offer to arrive") { b.store.getMessages(a.ip, 10).firstOrNull()?.status == MessageStatus.OFFERED }
        val offered = b.store.getMessages(a.ip, 10).single()

        b.engine.attachments.answerOffer(offered.id, accept = false, freeBytes = 0)

        waitFor("the refusal to reach the sender") {
            a.store.getAction(offered.id)?.status == MessageActionStatus.CANCELLED
        }
        assertEquals("declined", a.store.getAction(offered.id)?.payload?.failReason)
        assertEquals(MessageStatus.DECLINED, b.store.getMessage(offered.id)?.status)
    }

    @Test
    fun `a ping measures the round trip and an unknown peer does not answer`() = runBlocking {
        val (a, b) = pair()
        assertTrue("the peer answered", a.engine.transport.ping(b.ip, 5_000) >= 0)
        assertEquals(Transport.UNREACHABLE, a.engine.transport.ping("127.0.0.9", 300))
    }

    @Test
    fun `a blocked peer is not heard at all`() = runBlocking {
        val (a, b) = pair()
        b.store.upsertContact(a.ip, "alice")
        b.store.setContactFlags(a.ip, com.telenebula.core.model.ContactFlagsPatch(isBlocked = true))

        a.engine.outbox.sendText(b.ip, "let me in", null)
        // nothing is stored and nothing is acked: the send runs out its attempts instead
        delay(1_000)
        assertTrue(b.store.getMessages(a.ip, 10).isEmpty())
        assertTrue(a.store.getActionsForMessage(a.store.getMessages(b.ip, 10).single().id).none { it.status == MessageActionStatus.SUCCESS })
    }

    /**
     * The point of the whole design: an absent peer costs **one probe**, not one attempt per queued
     * action. Nothing is failed, and — the direct evidence — no action's attempt counter moves,
     * because attempts are not counted per action any more.
     */
    @Test
    fun `a queue for an absent peer is never failed and costs nothing per action`() = runBlocking {
        val (a, b) = pair()
        b.down()

        // spaced by a tick so the receiver's own ordering (ts, then id) reflects the order they
        // were sent in; without that the assertion below would be about id collation, not the queue
        a.engine.outbox.sendText(b.ip, "one", null)
        delay(5)
        a.engine.outbox.sendText(b.ip, "two", null)
        delay(5)
        a.engine.outbox.sendText(b.ip, "three", null)
        // comfortably past what the old ten-attempt budget would have burned through
        delay(3_000)

        val queued = a.store.pendingActionsForPeer(b.ip, 100)
        assertEquals(3, queued.size)
        assertTrue("silence must never fail an action", queued.all { it.status == MessageActionStatus.PENDING })
        assertTrue("attempts are counted per peer, not per action", queued.all { it.attempts == 0 })

        b.up()
        // generous: the first probe may spend its whole timeout on the connection A still had
        // cached, and only the one after it finds B listening again
        waitFor("the queue to drain when the peer returns", 45_000) {
            b.store.getMessages(a.ip, 10).size == 3
        }
        // and in the order they were sent, not whichever connected first
        assertEquals(listOf("one", "two", "three"), b.store.getMessages(a.ip, 10).map { it.body })
    }

    /**
     * A ping the user asked for is not just a diagnostic. If the peer has climbed to a long rung
     * and then comes back, tapping Ping must send what is waiting *now* rather than let it sit
     * until the next scheduled probe.
     */
    @Test
    fun `a manual ping that answers drains the queue at once`() = runBlocking {
        val (a, b) = pair()
        b.down()
        a.engine.outbox.sendText(b.ip, "waiting for you", null)

        // the ladder is jittered, so a fixed sleep can land either side of a scheduled probe
        waitFor("the probe ladder to climb past its fast rungs", 20_000) {
            val state = a.engine.delivery.stateOf(b.ip)
            state != null && !state.isDraining && (state.nextProbeInMs ?: 0) > PROBE_HEADROOM_MS
        }
        b.up()
        // nothing tells A that B is back, so the queue genuinely sits there
        assertEquals(0, b.store.getMessages(a.ip, 10).size)

        val rtt = a.engine.pingPeer(b.ip, null)
        assertTrue("the peer is back, so the ping must answer", rtt >= 0)
        waitFor("the ping to carry the queue with it", 5_000) {
            b.store.getMessages(a.ip, 10).size == 1
        }
        Unit
    }

    /**
     * Re-sending until acked means a message whose ack was lost arrives twice. The row is
     * insert-or-ignore, but the notification must not repeat — otherwise every reconnection would
     * announce old messages again.
     */
    @Test
    fun `a redelivered message is stored once and announced once`() = runBlocking {
        val (a, b) = pair()
        a.engine.outbox.sendText(b.ip, "only once", null)
        waitFor("the message to arrive") { b.store.getMessages(a.ip, 10).size == 1 }
        waitFor("the announcement") { b.events.count { it is CoreEvent.MessageReceived } == 1 }

        // exactly what a lost ack looks like from the sender's side
        val actionId = a.store.getMessages(b.ip, 10).single().id
        a.store.resetActionForRetry(actionId)
        a.engine.delivery.kick(b.ip)

        waitFor("the redelivery to be acked again") {
            a.store.getAction(actionId)?.status == MessageActionStatus.SUCCESS
        }
        assertEquals(1, b.store.getMessages(a.ip, 10).size)
        assertEquals(1, b.events.count { it is CoreEvent.MessageReceived })
    }

    /**
     * Both sides queued at each other. B answers an offer while A is away, so the answer has to
     * wait in B's own queue and go when A returns — which is exactly what these four frames being
     * durable buys.
     */
    @Test
    fun `an accepted offer reaches a sender that was away when it was accepted`() = runBlocking {
        val (a, b) = pair()
        val file = File(scratch, "big.bin").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(6 * 1024 * 1024) { it.toByte() })
        }
        a.engine.outbox.sendAttachment(
            b.ip,
            file.path,
            MessageAttachment(name = "big.bin", mime = "application/octet-stream", size = file.length(), uri = ""),
            null,
        )
        waitFor("the offer to arrive") { b.store.transferState(b.store.getMessages(a.ip, 10).firstOrNull()?.id.orEmpty()) != null }

        val transferId = b.store.getMessages(a.ip, 10).single().id
        // the sender goes away before the answer is given
        a.down()
        delay(300)
        b.engine.attachments.answerOffer(transferId, accept = true, freeBytes = Long.MAX_VALUE)

        // the decision is a durable action, not a frame written once and forgotten: that is what
        // lets it survive the sender being away, however long it takes them to come back
        val accept = b.store.getActionsForMessage(transferId).single()
        assertEquals(MessageActionType.ATT_ACCEPT, accept.type)

        a.up()
        waitFor("the acceptance to reach the sender and the file to stream", 40_000) {
            b.store.getMessage(transferId)?.attachment?.uri != null
        }
        Unit
    }

    @Test
    fun `stopping an engine closes its connections and frees its port`() = runBlocking {
        val (a, b) = pair()
        a.engine.outbox.sendText(b.ip, "before the stop", null)
        waitFor("the message to arrive") { b.store.getMessages(a.ip, 10).isNotEmpty() }
        assertTrue(a.engine.transport.connectedPeers().isNotEmpty())

        val port = b.engine.profile.msgPort
        b.stop()
        nodes.remove(b)

        waitFor("the link to drop") { a.engine.transport.connectedPeers().isEmpty() }
        // the listener really let go of the socket
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.2"), port))
        }
    }

    /** A frame written by hand, as a peer that does not play by the rules would. */
    private suspend fun rawBegin(from: Node, to: Node, id: String, size: Long) {
        val link = from.engine.transport.link(to.ip)
        val begin = from.engine.transport.envelope(EnvelopeType.ATT_BEGIN).copy(
            id = id,
            name = "x.bin",
            mime = "application/octet-stream",
            size = size,
            totalChunks = TransferManager.chunkCount(size),
            seq = 0,
        )
        assertTrue(from.engine.transport.send(link, begin))
    }

    private suspend fun rawChunk(from: Node, to: Node, id: String, seq: Long, bytes: ByteArray) {
        val link = from.engine.transport.link(to.ip)
        val chunk = from.engine.transport.envelope(EnvelopeType.ATT_CHUNK).copy(
            transferId = id,
            seq = seq,
            dataB64 = Base64.getEncoder().encodeToString(bytes),
        )
        assertTrue(from.engine.transport.send(link, chunk))
    }

    /** Anything after this frame on the same connection has been handled, so a refused frame has had its chance. */
    private suspend fun settle(from: Node, to: Node, marker: String) {
        from.engine.outbox.sendText(to.ip, marker, null)
        waitFor("the marker to arrive") { to.store.getMessages(from.ip, 50).any { it.body == marker } }
    }

    @Test
    fun `a transfer id cannot steer the file out of the attachments directory`() = runBlocking {
        val (a, b) = pair()
        val evil = "../../escaped"
        rawBegin(a, b, evil, 10)
        rawChunk(a, b, evil, 0, ByteArray(10) { 1 })

        waitFor("the transfer to land somewhere") { b.store.getMessage(evil)?.attachment?.uri != null }
        val uri = b.store.getMessage(evil)?.attachment?.uri ?: error("no uri")
        assertTrue("stored under the attachments dir", CorePaths.isInside(b.attachments, File(CorePaths.uriToPath(uri))))
        assertFalse(File(scratch, "escaped").exists())
        assertFalse(File(scratch, "bob/escaped").exists())
    }

    @Test
    fun `a peer cannot attach a file to a message we sent by reusing its id`() = runBlocking {
        val (a, b) = pair()
        b.engine.outbox.sendText(a.ip, "mine", null)
        waitFor("the message to arrive") { a.store.getMessages(b.ip, 10).isNotEmpty() }
        val mine = b.store.getMessages(a.ip, 10).single()

        rawBegin(a, b, mine.id, 10)
        rawChunk(a, b, mine.id, 0, ByteArray(10) { 1 })
        settle(a, b, "after the hijack attempt")

        val still = b.store.getMessage(mine.id) ?: error("message gone")
        assertEquals(MessageDirection.OUT, still.direction)
        assertNull(still.attachment)
        assertFalse(b.engine.incoming.containsKey(mine.id))
    }

    @Test
    fun `a chunk larger than announced ends the transfer and leaves no file`() = runBlocking {
        val (a, b) = pair()
        val id = "oversized"
        rawBegin(a, b, id, 10)
        rawChunk(a, b, id, 0, ByteArray(70_000) { 2 })
        settle(a, b, "after the oversized chunk")

        assertNull(b.store.getMessage(id)?.attachment?.uri)
        assertFalse(b.engine.incoming.containsKey(id))
        assertTrue(b.attachments.listFiles().orEmpty().none { it.name.startsWith(id) })
    }

    @Test
    fun `a short chunk before the last one ends the transfer`() = runBlocking {
        val (a, b) = pair()
        val id = "short"
        val size = Limits.ATT_CHUNK_BYTES.toLong() + 10
        rawBegin(a, b, id, size)
        rawChunk(a, b, id, 0, ByteArray(100) { 3 })
        rawChunk(a, b, id, 1, ByteArray(10) { 3 })
        settle(a, b, "after the short chunk")

        assertNull(b.store.getMessage(id)?.attachment?.uri)
        assertFalse(b.engine.incoming.containsKey(id))
    }

    @Test
    fun `withdrawing an unanswered offer removes it from the receiver`() = runBlocking {
        val (a, b) = pair()
        val id = offered(a, b, "withdrawn-early.bin")

        a.engine.outbox.cancelAction(id)

        waitFor("the receiver to drop the offer") { b.store.transferState(id) == Wire.TransferState.CANCELLED }
        assertEquals(MessageActionStatus.CANCELLED, a.store.getAction(id)?.status)
    }

    @Test
    fun `a third party cannot accept, cancel or restart someone else's transfer`() = runBlocking {
        val (a, b) = pair()
        val c = third(a)
        val id = offered(a, b, "private.bin")

        // carol tells alice that "bob" accepted, and tells bob that "alice" withdrew
        val toA = c.engine.transport.link(a.ip)
        assertTrue(c.engine.transport.send(toA, c.engine.transport.envelope(EnvelopeType.ATT_ACCEPT).copy(targetId = id, seq = 0)))
        val toB = c.engine.transport.link(b.ip)
        assertTrue(c.engine.transport.send(toB, c.engine.transport.envelope(EnvelopeType.ATT_CANCEL).copy(targetId = id)))
        assertTrue(c.engine.transport.send(toB, c.engine.transport.envelope(EnvelopeType.ATT_ERROR).copy(targetId = id, reason = "no-space")))
        settle(c, a, "carol was here")
        settle(c, b, "carol was here too")

        assertEquals(MessageActionStatus.WAITING, a.store.getAction(id)?.status)
        assertEquals(Wire.TransferState.OFFERED, b.store.transferState(id))
        assertEquals(MessageStatus.OFFERED, b.store.getMessage(id)?.status)
    }

    @Test
    fun `a large begin that skipped the offer is refused`() = runBlocking {
        val (a, b) = pair()
        val id = "unoffered"
        rawBegin(a, b, id, Limits.ATT_AUTO_ACCEPT_BYTES + 1)
        settle(a, b, "after the unoffered begin")

        assertNull(b.store.getMessage(id))
        assertFalse(b.engine.incoming.containsKey(id))
    }

    @Test
    fun `a late refusal after a transfer finished changes nothing`() = runBlocking {
        val (a, b) = pair()
        val bytes = ByteArray(2048) { 5 }
        val source = File(scratch, "alice/attachments/done.bin").apply { writeBytes(bytes) }
        a.engine.outbox.sendAttachment(b.ip, source.path, MessageAttachment(name = "done.bin", mime = "application/octet-stream", size = bytes.size.toLong()), null)
        waitFor("the send to complete") { a.store.getMessages(b.ip, 10).firstOrNull()?.status == MessageStatus.DELIVERED }
        val id = a.store.getMessages(b.ip, 10).single().id

        val link = b.engine.transport.link(a.ip)
        assertTrue(b.engine.transport.send(link, b.engine.transport.envelope(EnvelopeType.ATT_ERROR).copy(targetId = id, reason = "no-space")))
        settle(b, a, "after the late error")

        assertEquals(MessageStatus.DELIVERED, a.store.getMessage(id)?.status)
        assertEquals(MessageActionStatus.SUCCESS, a.store.getAction(id)?.status)
    }

    @Test
    fun `an engine whose port is taken says so instead of silently receiving nothing`() = runBlocking {
        val (a, _) = pair()
        val clash = Node("127.0.0.1", "alice-again", a.port).also { nodes += it }
        waitFor("the fault to be announced") { clash.events.any { it is CoreEvent.EngineFault && it.what.contains("listen") } }
        assertTrue(a.events.none { it is CoreEvent.EngineFault })
    }
}

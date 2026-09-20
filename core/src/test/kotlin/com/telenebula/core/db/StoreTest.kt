package com.telenebula.core.db

import com.telenebula.core.CoreException
import com.telenebula.core.model.ContactPrivacyPrefs
import com.telenebula.core.model.CallLog
import com.telenebula.core.model.CallOutcome
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.ContactFlagsPatch
import com.telenebula.core.model.ContactNotificationPrefs
import com.telenebula.core.model.CoverRevealGate
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import com.telenebula.core.model.MessageAttachment
import com.telenebula.core.model.MessageCursor
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The storage rules, ported from the Rust core's own store tests against a real SQLite. */
class StoreTest {
    private val scratch = File(System.getProperty("java.io.tmpdir"), "tn-store-${System.nanoTime()}")
    private val stores = ArrayList<Store>()

    @After
    fun tearDown() {
        stores.forEach { runCatching { it.close() } }
        scratch.deleteRecursively()
    }

    /** The connection behind the last [store], for the cases that write a row the store cannot. */
    private lateinit var db: SqlDb

    private fun store(name: String = "main.db"): Store {
        scratch.mkdirs()
        val path = File(scratch, name).path
        val opened = JdbcSqlDb.open(path)
        Schema.apply(opened)
        db = opened
        return Store(opened, path, JdbcSqlDb::open).also { stores += it }
    }

    private fun message(
        id: String,
        peer: String = "fd::1",
        direction: MessageDirection = MessageDirection.IN,
        body: String = "",
        ts: Long = 1,
    ) = ChatMessage(
        id = id,
        peerIp = peer,
        direction = direction,
        body = body,
        ts = ts,
        status = if (direction == MessageDirection.OUT) MessageStatus.PENDING else MessageStatus.RECEIVED,
        kind = MessageKind.TEXT,
        isRead = direction == MessageDirection.OUT,
    )

    private fun action(
        id: String,
        messageId: String = id,
        type: MessageActionType = MessageActionType.SEND,
        status: MessageActionStatus = MessageActionStatus.PENDING,
        peer: String = "fd::1",
    ) = MessageAction(
        id = id,
        messageId = messageId,
        peerIp = peer,
        type = type,
        status = status,
        attempts = 0,
        createdAt = 1,
        updatedAt = 1,
    )

    @Test
    fun `disappearing messages expire after reading, and search finds their text`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.insertMessage(message("m1", body = "secret plans").copy(expireSecs = 0))

        // unread: no timer yet
        assertTrue(store.sweepExpired(System.currentTimeMillis()).isEmpty())
        store.markChatRead("fd::1")
        assertNotNull(store.getMessage("m1")?.expiresAt)

        // search works while it lives, and % / _ stay literal
        assertEquals(1, store.searchMessages("fd::1", "secret", 10).size)
        assertEquals(0, store.searchMessages("fd::1", "%", 10).size)

        val swept = store.sweepExpired(System.currentTimeMillis() + 1)
        assertEquals(1, swept.size)
        assertNull(store.getMessage("m1"))

        // an outgoing copy carries expires_at from the start
        store.insertMessage(
            message("m2", direction = MessageDirection.OUT, body = "bye", ts = 2)
                .copy(expireSecs = 60, expiresAt = System.currentTimeMillis() + 60_000),
        )
        assertTrue(store.sweepExpired(System.currentTimeMillis()).isEmpty())

        store.setClientVersion("fd::1", "1.2.3")
        assertEquals("1.2.3", store.getContact("fd::1")?.clientVersion)
    }

    @Test
    fun `a covered message says only that in the chat list, and stops being covered once it is gone`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.insertMessage(message("m1", body = "the real thing").copy(isCovered = true))

        assertTrue(store.getMessage("m1")?.isCovered == true)
        assertEquals("the real thing", store.getMessage("m1")?.body)
        assertEquals("Covered message", store.getChatSummaries().first().lastBody)

        store.markDeleted("m1")
        store.wipeDeletedContent("m1")
        assertFalse(store.getMessage("m1")?.isCovered == true)
        assertEquals("Message deleted", store.getChatSummaries().first().lastBody)
    }

    @Test
    fun `a covered message is in neither the media gallery nor the link list`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.insertMessage(
            message("m1", body = "see https://example.com/a").copy(
                isCovered = true,
                kind = MessageKind.IMAGE,
                attachment = MessageAttachment(name = "photo.jpg", mime = "image/jpeg", size = 10, uri = "file:///tmp/photo.jpg"),
            ),
        )
        store.insertMessage(message("m2", body = "open https://example.com/b", ts = 2))

        assertTrue(store.getChatMedia("fd::1", 10).isEmpty())
        assertEquals(0, store.countChatMedia("fd::1"))
        assertEquals(listOf("https://example.com/b"), store.getChatLinks("fd::1", 10).map { it.url })
        assertEquals(1, store.countChatLinkMessages("fd::1"))
    }

    @Test
    fun `a chat remembers which gate it asks for before a cover comes off`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        assertNull(store.getContact("fd::1")?.privacy?.revealGate)

        store.setContactPrivacy("fd::1", ContactPrivacyPrefs(revealGate = CoverRevealGate.CODE))
        assertEquals(CoverRevealGate.CODE, store.getContact("fd::1")?.privacy?.revealGate)

        store.setContactPrivacy("fd::1", ContactPrivacyPrefs())
        assertNull(store.getContact("fd::1")?.privacy?.revealGate)
    }

    @Test
    fun `a transfer this side cancelled is told apart from one the sender withdrew`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        val file = MessageAttachment(name = "a.bin", mime = "application/octet-stream", size = 10)
        store.insertMessage(message("mine").copy(kind = MessageKind.FILE, status = MessageStatus.CANCELLED, attachment = file))
        store.insertMessage(message("theirs", ts = 2).copy(kind = MessageKind.FILE, status = MessageStatus.CANCELLED, attachment = file))
        store.upsertTransfer("mine", "fd::1", isIncoming = true, state = Wire.TransferState.RECEIVING, size = 10)
        store.upsertTransfer("theirs", "fd::1", isIncoming = true, state = Wire.TransferState.RECEIVING, size = 10)
        store.setTransferState("mine", Wire.TransferState.CANCELLED, MessagesDao.CANCELLED_BY_RECEIVER)
        store.setTransferState("theirs", Wire.TransferState.CANCELLED)

        assertEquals(setOf("mine"), store.getChatView("fd::1", 10).cancelledByMe)
    }

    @Test
    fun `changing a contact address moves its history and merges a duplicate`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.updateContactDetails("fd::1", "alice", "Ally", "old cert")
        store.insertMessage(message("m1", body = "hello"))
        // the peer already wrote from its new address, which auto-created a contact
        store.syncContact("fd::9", "alice-new")
        store.insertMessage(message("m2", peer = "fd::9", body = "new cert", ts = 2))

        store.changeContactIp("fd::1", "fd::9")

        assertNull(store.getContact("fd::1"))
        val merged = store.getContact("fd::9")
        assertEquals("Ally", merged?.nickname)
        assertEquals("alice-new", merged?.name)
        assertEquals(2, store.getMessages("fd::9", 10).size)
        assertTrue(store.getMessages("fd::1", 10).isEmpty())
        assertThrows(CoreException::class.java) { store.changeContactIp("fd::1", "fd::2") }
    }

    @Test
    fun `merging a backup adds missing rows and rewrites attachment directories`() {
        val source = store("src.db")
        source.upsertContact("fd::1", "alice")
        source.insertMessage(message("m1", body = "from backup"))
        source.insertMessage(
            message("m2", direction = MessageDirection.OUT, ts = 2).copy(
                kind = MessageKind.FILE,
                attachment = MessageAttachment(
                    name = "doc.pdf",
                    mime = "application/pdf",
                    size = 3,
                    uri = "file:///old/attachments/x-doc.pdf",
                ),
            ),
        )
        source.close()

        val target = store("target.db")
        target.upsertContact("fd::1", "Alice (local)")
        target.insertMessage(message("m1", body = "already here"))

        val (contacts, messages) = target.mergeFrom(File(scratch, "src.db").path, "/old/attachments", "/new/attachments")

        assertEquals(0, contacts)
        assertEquals(1, messages)
        assertEquals("Alice (local)", target.getContact("fd::1")?.name)
        assertEquals("already here", target.getMessage("m1")?.body)
        assertEquals("file:///new/attachments/x-doc.pdf", target.getMessage("m2")?.attachment?.uri)
    }

    @Test
    fun `links and media are found per chat`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.insertMessage(message("m1", body = "see https://example.com/a, and http://x.io/b."))
        store.insertMessage(message("m2", direction = MessageDirection.OUT, body = "no link here", ts = 2))
        store.insertMessage(
            message("m3", ts = 3).copy(
                kind = MessageKind.IMAGE,
                attachment = MessageAttachment("a.jpg", "image/jpeg", 10, uri = "file:///x/a.jpg", width = 1200, height = 800),
            ),
        )

        assertEquals(
            listOf("https://example.com/a", "http://x.io/b"),
            store.getChatLinks("fd::1", 50).map { it.url },
        )
        assertEquals(1, store.getChatMedia("fd::1", 50).size)
        assertEquals(listOf("https://a.b/c", "HTTPS://D.E"), Store.extractUrls("(https://a.b/c) HTTPS://D.E"))
    }

    @Test
    fun `seen receipts stamp outgoing messages and reset on an edit`() {
        val store = store()
        store.insertMessage(message("m1", direction = MessageDirection.OUT, body = "hi"))
        store.insertMessage(message("m2", body = "yo", ts = 2))

        // the peer saw m1; a stray id and a wrong direction are ignored
        assertTrue(store.setSeenByPeer(listOf("m1", "m2", "zz"), "fd::1"))
        assertNotNull(store.getMessage("m1")?.seenAt)
        assertNull(store.getMessage("m2")?.seenAt)

        // our own edit makes it unseen again
        assertTrue(store.applyEdit("m1", "hi there", null))
        assertNull(store.getMessage("m1")?.seenAt)

        // incoming: nothing to report until read, and the flag is only set once the peer acked,
        // which is what lets one queued receipt carry many ids and survive a restart mid-queue
        assertTrue(store.unreportedSeenIds("fd::1", 100).isEmpty())
        store.markChatRead("fd::1")
        assertEquals(listOf("m2"), store.unreportedSeenIds("fd::1", 100))
        // reading it again still reports it: nothing is flagged until it is actually delivered
        assertEquals(listOf("m2"), store.unreportedSeenIds("fd::1", 100))
        store.markSeenReported(listOf("m2"))
        assertTrue(store.unreportedSeenIds("fd::1", 100).isEmpty())

        // the peer's edit must be reported again
        assertTrue(store.applyEdit("m2", "yo!", "fd::1"))
        assertEquals(listOf("m2"), store.unreportedSeenIds("fd::1", 100))
        // but the same edit arriving twice must not re-arm it, or a redelivery grows the queue
        assertFalse(store.applyEdit("m2", "yo!", "fd::1"))
        store.markSeenReported(listOf("m2"))
        assertTrue(store.unreportedSeenIds("fd::1", 100).isEmpty())

        store.createAction(action("s1", messageId = "m2", type = MessageActionType.SEEN))
        assertEquals(1, store.getActionsForMessage("m2").size)

        assertEquals(listOf("s1"), store.pendingSeenActionsFor(listOf("m1", "m2", "zz")).map { it.id })
        store.setActionStatus("s1", MessageActionStatus.SUCCESS)
        assertTrue(store.pendingSeenActionsFor(listOf("m2")).isEmpty())

        assertTrue(store.unreportedSeenIds("fd::1", 100).isEmpty())
        store.markSeenUnreported("m2")
        assertEquals(listOf("m2"), store.unreportedSeenIds("fd::1", 100))
        store.markSeenUnreported("m1")
        assertEquals(listOf("m2"), store.unreportedSeenIds("fd::1", 100))
    }

    @Test
    fun `a v1 database migrates and round trips`() {
        scratch.mkdirs()
        val path = File(scratch, "v1.db").path
        val db = JdbcSqlDb.open(path)
        // an install from before the action pipeline: no kind/edited/… columns, older CHECKs
        db.execute(
            """
            CREATE TABLE contacts (ip TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL,
              added_at INTEGER NOT NULL, last_seen_at INTEGER)
            """.trimIndent(),
        )
        db.execute(
            """
            CREATE TABLE messages (id TEXT PRIMARY KEY NOT NULL, peer_ip TEXT NOT NULL,
              direction TEXT NOT NULL, body TEXT NOT NULL, ts INTEGER NOT NULL,
              status TEXT NOT NULL, read INTEGER NOT NULL DEFAULT 0)
            """.trimIndent(),
        )
        db.execute(
            """
            CREATE TABLE message_actions (
              id TEXT PRIMARY KEY NOT NULL, message_id TEXT NOT NULL, peer_ip TEXT NOT NULL,
              type TEXT NOT NULL CHECK (type IN ('send','react','edit','delete')),
              payload_json TEXT,
              status TEXT NOT NULL CHECK (status IN ('pending','success','failed')),
              attempts INTEGER NOT NULL DEFAULT 0, max_attempts INTEGER NOT NULL DEFAULT 10,
              created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
            """.trimIndent(),
        )
        db.execute("INSERT INTO messages VALUES ('m1','fd::1','in','hi',1,'received',0)")

        Schema.apply(db)
        val store = Store(db, path, JdbcSqlDb::open).also { stores += it }

        // the rebuilt table accepts the statuses and types the old CHECK refused
        store.createAction(action("a1", messageId = "m1", type = MessageActionType.REACT, status = MessageActionStatus.CANCELLED))
        store.createAction(action("a2", messageId = "m1", type = MessageActionType.SEEN, status = MessageActionStatus.WAITING))
        store.createAction(action("a3", messageId = "m1", type = MessageActionType.ATT_ACCEPT))
        assertEquals(3, store.getActionsForMessage("m1").size)

        val migrated = store.getMessage("m1")
        assertEquals(MessageKind.TEXT, migrated?.kind)
        assertEquals(false, migrated?.isDeleted)
    }

    @Test
    fun `the chat view embeds actions and reply sources`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.insertMessage(message("m1", body = "first"))
        store.insertMessage(message("m2", direction = MessageDirection.OUT, body = "reply", ts = 2).copy(replyToId = "m1"))
        store.createAction(action("m2"))

        val view = store.getChatView("fd::1", 200)
        assertEquals(2, view.messages.size)
        assertEquals("alice", view.contact?.name)
        assertEquals(1, view.actions["m2"]?.size)
        assertEquals("first", view.replySources["m1"]?.body)

        val rows = store.getChatRows("fd::1", listOf("m2", "not-there"))
        assertEquals(listOf("m2"), rows.messages.map { it.id })
        assertEquals(1, rows.actions["m2"]?.size)
        assertEquals("first", rows.replySources["m1"]?.body)
        assertEquals("alice", rows.contact?.name)
    }

    @Test
    fun `keyset paging walks a chat backwards without gaps or repeats`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        // pairs share a timestamp, so the id is what orders them
        for (i in 1..25) store.insertMessage(message("m%02d".format(i), body = "b$i", ts = (i / 2).toLong() + 1))
        val all = (1..25).map { "m%02d".format(it) }

        val head = store.getChatView("fd::1", 10).messages
        assertEquals(all.takeLast(10), head.map { it.id })

        val page = store.getMessagesBefore("fd::1", MessageCursor(head.first().ts, head.first().id), 10)
        assertEquals(all.drop(5).take(10), page.map { it.id })

        val window = store.getChatView("fd::1", MessageCursor(page.first().ts, page.first().id), 100).messages
        assertEquals(page.map { it.id } + head.map { it.id }, window.map { it.id })
        // the cap keeps the old end and drops the newest, so a reader scrolled back does not lose its place
        assertEquals(page.map { it.id }.take(4), store.getChatView("fd::1", MessageCursor(page.first().ts, page.first().id), 4).messages.map { it.id })

        val rest = store.getMessagesBefore("fd::1", MessageCursor(page.first().ts, page.first().id), 10)
        assertEquals(all.take(5), rest.map { it.id })
        assertTrue(store.getMessagesBefore("fd::1", MessageCursor(rest.first().ts, rest.first().id), 10).isEmpty())
    }

    @Test
    fun `reactions, edits and deletes follow their author rules`() {
        val store = store()
        store.insertMessage(message("m1", body = "hello"))
        assertTrue(store.setReaction("m1", "fd::9", "👍"))

        // author guard: an edit from the wrong peer is refused
        assertFalse(store.applyEdit("m1", "hacked", "fd::2"))
        assertTrue(store.applyEdit("m1", "edited", "fd::1"))

        // a tombstone keeps the content until the wipe
        store.markDeleted("m1")
        store.unmarkDeleted("m1")
        val restored = store.getMessage("m1")
        assertEquals("edited", restored?.body)
        assertEquals(true, restored?.isEdited)
        assertEquals(mapOf("fd::9" to "👍"), restored?.reactions)

        assertTrue(store.applyDeleteForEveryone("m1", "fd::1"))
        val deleted = store.getMessage("m1")
        assertEquals(true, deleted?.isDeleted)
        assertEquals("", deleted?.body)
        assertEquals(emptyMap<String, String>(), deleted?.reactions)
    }

    @Test
    fun `syncing a contact fills and updates the username but keeps the nickname`() {
        val store = store()
        // created from the form with a nickname only
        store.upsertContact("fd::1", "")
        store.updateContactDetails("fd::1", "", "Bestie", "")

        assertFalse("an empty announcement changes nothing", store.syncContact("fd::1", ""))
        assertTrue("the peer's hello brings the real username", store.syncContact("fd::1", "alice"))
        assertFalse("the same name again is not a change", store.syncContact("fd::1", "alice"))

        val contact = store.getContact("fd::1")
        assertEquals("alice", contact?.name)
        assertEquals("Bestie", contact?.nickname)
        // chats still prefer the nickname
        assertEquals("Bestie", store.getChatSummaries().firstOrNull()?.name)
    }

    @Test
    fun `a contact created by syncing counts as a change`() {
        val store = store()
        assertTrue(store.syncContact("fd::5", ""))
        assertNotNull(store.getContact("fd::5"))
    }

    @Test
    fun `call logs round trip newest first`() {
        val store = store()
        for ((id, started, outcome) in listOf(
            Triple("c1", 10L, CallOutcome.MISSED),
            Triple("c2", 20L, CallOutcome.ANSWERED),
        )) {
            store.insertCallLog(
                CallLog(
                    id = id,
                    peerIp = "fd::1",
                    direction = MessageDirection.IN,
                    isVideo = false,
                    outcome = outcome,
                    startedAt = started,
                    connectedAt = if (outcome == CallOutcome.ANSWERED) started + 1 else null,
                    endedAt = started + 5,
                ),
            )
        }
        val logs = store.getCallLogs("fd::1", 10)
        assertEquals(listOf("c2", "c1"), logs.map { it.id })
        assertEquals(21L, logs.first().connectedAt)
        assertEquals(2, store.getAllCallLogs(10).size)

        store.deleteCallLogs(listOf("c1", "never-existed"))
        assertEquals(listOf("c2"), store.getAllCallLogs(10).map { it.id })

        store.deleteContact("fd::1")
        assertTrue(store.getCallLogs("fd::1", 10).isEmpty())
    }

    @Test
    fun `contact flags drive pin order, blocking and the manual unread mark`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.upsertContact("fd::2", "bob")
        store.insertMessage(message("m1", peer = "fd::1", body = "newer", ts = 200))
        store.insertMessage(message("m2", peer = "fd::2", body = "older", ts = 100))

        // bob is pinned → first despite the older message
        store.setContactFlags(
            "fd::2",
            ContactFlagsPatch(isPinned = true, isMarkedUnread = true, muteUntil = MUTE_FOREVER),
        )
        val list = store.getChatSummaries()
        assertEquals("fd::2", list.firstOrNull()?.ip)
        assertEquals(true, list.firstOrNull()?.isMarkedUnread)
        assertEquals(MUTE_FOREVER, list.firstOrNull()?.muteUntil)

        // reading clears the manual unread flag
        store.markChatRead("fd::2")
        val bob = store.getContact("fd::2")
        assertEquals(false, bob?.isMarkedUnread)
        assertNotNull(bob?.pinnedAt)

        store.setContactFlags("fd::1", ContactFlagsPatch(isBlocked = true))
        assertTrue(store.isBlocked("fd::1"))
        assertFalse(store.isBlocked("fd::2"))

        store.setContactNotifications("fd::1", ContactNotificationPrefs(useGlobal = false, messages = false))
        assertEquals(false, store.getContact("fd::1")?.notifications?.messages)
        store.setContactNotifications("fd::1", null)
        assertNull(store.getContact("fd::1")?.notifications)

        store.setContactPrivacy("fd::1", ContactPrivacyPrefs(sendReadReceipts = false))
        assertEquals(false, store.getContact("fd::1")?.privacy?.sendReadReceipts)
        assertNull(store.getContact("fd::1")?.privacy?.sendTypingIndicators)
        store.setContactPrivacy("fd::1", ContactPrivacyPrefs(sendTypingIndicators = true, blockScreenshots = true))
        assertNull(store.getContact("fd::1")?.privacy?.sendReadReceipts)
        assertEquals(true, store.getContact("fd::1")?.privacy?.sendTypingIndicators)
        assertEquals(true, store.getContact("fd::1")?.privacy?.blockScreenshots)
    }

    @Test
    fun `summaries group the unread count and the last message`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.insertMessage(message("m1", body = "one"))
        store.insertMessage(message("m2", body = "two", ts = 2))

        val first = store.getChatSummaries().first()
        assertEquals(2, first.unread)
        assertEquals("two", first.lastBody)

        store.markChatRead("fd::1")
        assertEquals(0, store.getChatSummaries().first().unread)
    }

    @Test
    fun `summaries expose the last message's send status`() {
        val store = store()
        store.upsertContact("fd::1", "alice")

        store.insertMessage(message("m1", direction = MessageDirection.OUT, body = "one", ts = 1))
        store.createAction(action("m1", status = MessageActionStatus.SUCCESS))
        assertEquals(MessageActionStatus.SUCCESS, lastSendStatus(store))

        // an incoming last message has no send action of its own, and must not inherit m1's
        store.insertMessage(message("m2", body = "two", ts = 2))
        assertNull(lastSendStatus(store))

        store.insertMessage(message("m3", direction = MessageDirection.OUT, body = "three", ts = 3))
        store.createAction(action("m3"))
        assertEquals(MessageActionStatus.PENDING, lastSendStatus(store))

        // the message status stays 'pending' either way, so the action is the only signal
        store.setActionStatus("m3", MessageActionStatus.FAILED)
        assertEquals(MessageActionStatus.FAILED, lastSendStatus(store))
        store.setActionStatus("m3", MessageActionStatus.CANCELLED)
        assertEquals(MessageActionStatus.CANCELLED, lastSendStatus(store))
    }

    /**
     * The receiver tells a finished transfer from one still arriving by the stored file, not by the
     * row: a row exists from the first frame, so acking on that alone would confirm a transfer that
     * never completed.
     */
    @Test
    fun `a half received attachment is distinguishable from a finished one`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.insertMessage(
            message("t1").copy(
                kind = MessageKind.IMAGE,
                status = MessageStatus.RECEIVING,
                attachment = MessageAttachment("a.jpg", "image/jpeg", 99),
            ),
        )
        assertNotNull("the row exists while bytes are still arriving", store.getMessage("t1")?.attachment)
        assertNull("but it has no file yet", store.getMessage("t1")?.attachment?.uri)

        store.setMessageAttachment("t1", MessageAttachment("a.jpg", "image/jpeg", 99, uri = "file:///x/t1-a.jpg"))
        store.setMessageStatus("t1", MessageStatus.RECEIVED)
        val done = store.getMessage("t1")
        assertNotNull(done?.attachment?.uri)
        assertEquals(MessageStatus.RECEIVED, done?.status)
    }

    /**
     * The offer state machine is what lets a large transfer wait with no timeout: the decision is
     * durable, so a lost answer is recovered by the sender's next re-offer rather than by a timer.
     */
    @Test
    fun `an offer records its decision and its resume point`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.upsertTransfer("t1", "fd::1", isIncoming = true, state = Wire.TransferState.OFFERED, size = 40L * 1024 * 1024)

        assertEquals(Wire.TransferState.OFFERED, store.transferState("t1"))
        assertFalse("an offer is not consent", store.transferIsAccepted("t1"))
        assertEquals(listOf("t1"), store.pendingTransfersForPeer("fd::1", isIncoming = true))

        store.setTransferState("t1", Wire.TransferState.ACCEPTED)
        assertTrue(store.transferIsAccepted("t1"))
        assertTrue("an answered offer is no longer waiting", store.pendingTransfersForPeer("fd::1", true).isEmpty())

        assertEquals(0L, store.transferReceived("t1"))
        store.setTransferReceived("t1", 12)
        assertEquals(12L, store.transferReceived("t1"))

        store.upsertTransfer("t2", "fd::1", true, Wire.TransferState.OFFERED, 1)
        store.setTransferState("t2", Wire.TransferState.DECLINED, "no-space")
        assertEquals(Wire.TransferState.DECLINED, store.transferState("t2"))
        assertFalse(store.transferIsAccepted("t2"))
        // an unknown transfer is not accepted either, which is what gates an unasked-for stream
        assertFalse(store.transferIsAccepted("never-heard-of-it"))
    }

    /**
     * A read receipt says "I have this". Opening the chat while a file is still arriving must not
     * send one, or the sender is told it landed before any of it did.
     */
    @Test
    fun `a receipt waits for the file to finish arriving`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.insertMessage(message("t1").copy(kind = MessageKind.FILE, status = MessageStatus.RECEIVING))
        store.insertMessage(message("m1", body = "hello", ts = 2))

        store.markChatRead("fd::1")
        assertEquals(listOf("m1"), store.unreportedSeenIds("fd::1", 100))
        store.markSeenReported(listOf("m1"))

        store.setMessageStatus("t1", MessageStatus.RECEIVED)
        assertEquals(listOf("t1"), store.unreportedSeenIds("fd::1", 100))
        store.markSeenReported(listOf("t1"))
        assertTrue("reported once", store.unreportedSeenIds("fd::1", 100).isEmpty())
    }

    @Test
    fun `media lists only attachments that finished arriving`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        for ((id, status) in listOf(
            "m1" to MessageStatus.OFFERED,
            "m2" to MessageStatus.RECEIVING,
            "m3" to MessageStatus.DECLINED,
            "m4" to MessageStatus.RECEIVED,
            "m5" to MessageStatus.CANCELLED,
        )) {
            store.insertMessage(
                message(id).copy(
                    kind = MessageKind.IMAGE,
                    status = status,
                    attachment = MessageAttachment("$id.jpg", "image/jpeg", 10),
                ),
            )
        }
        assertEquals(listOf("m4"), store.getChatMedia("fd::1", 50).map { it.id })
    }

    /**
     * The partial file of a running transfer is referenced by nothing, so the orphan sweep has to
     * be told about it or it deletes the progress a resume would pick up.
     */
    @Test
    fun `a running transfer is known to the orphan sweep`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.upsertTransfer("t1", "fd::1", true, Wire.TransferState.RECEIVING, 999)
        store.upsertTransfer("t2", "fd::1", true, Wire.TransferState.OFFERED, 999)
        store.upsertTransfer("t3", "fd::1", true, Wire.TransferState.COMPLETE, 999)
        assertEquals(listOf("t1", "t2"), store.unfinishedTransferIds().sorted())
    }

    @Test
    fun `messages with the same timestamp keep a stable order`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        // without a tiebreak SQLite may order these either way, and a tie straddling the page
        // limit could drop an arbitrary one of them
        for (id in listOf("mc", "ma", "mb")) store.insertMessage(message(id, body = id, ts = 7))

        assertEquals(listOf("ma", "mb", "mc"), store.getMessages("fd::1", 50).map { it.id })
        assertEquals(listOf("mb", "mc"), store.getMessages("fd::1", 2).map { it.id })
    }

    @Test
    fun `incoming messages carry their read state until the chat is opened`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.insertMessage(message("in1", body = "hi"))
        store.insertMessage(message("out1", direction = MessageDirection.OUT, body = "hey", ts = 2))

        assertEquals(listOf(false, true), store.getMessages("fd::1", 50).map { it.isRead })
        store.markChatRead("fd::1")
        assertTrue(store.getMessages("fd::1", 50).all { it.isRead })
    }

    @Test
    fun `the outbox backfills send actions for messages that predate it`() {
        val store = store()
        store.insertMessage(message("m1", direction = MessageDirection.OUT, body = "old"))
        store.backfillSendActions()
        val action = store.getActionsForMessage("m1").single()
        assertEquals(MessageActionType.SEND, action.type)
        // running it again changes nothing
        store.backfillSendActions()
        assertEquals(1, store.getActionsForMessage("m1").size)
    }

    /**
     * The guard in [Schema.migrateActionsTable] is what decides whether an install rebuilds. The v1
     * fixture above is not enough to test it: the risk is an install already on the *current*
     * schema, which every earlier literal matches, so only the newest one forces the rebuild.
     * Miss that and the first attachment answer ever queued violates the CHECK.
     */
    @Test
    fun `a database at the previous schema is rebuilt for the attachment action types`() {
        scratch.mkdirs()
        val path = File(scratch, "prev.db").path
        val db = JdbcSqlDb.open(path)
        db.execute(
            """
            CREATE TABLE message_actions (
              id TEXT PRIMARY KEY NOT NULL, message_id TEXT NOT NULL, peer_ip TEXT NOT NULL,
              type TEXT NOT NULL CHECK (type IN ('send','react','edit','delete','seen')),
              payload_json TEXT,
              status TEXT NOT NULL CHECK (status IN ('pending','waiting','success','failed','cancelled')),
              attempts INTEGER NOT NULL DEFAULT 0, max_attempts INTEGER NOT NULL DEFAULT 10,
              created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
            """.trimIndent(),
        )
        db.execute(
            "INSERT INTO message_actions VALUES ('a1','m1','fd::1','send',NULL,'success',3,10,1,1)",
        )
        Schema.apply(db)
        val store = Store(db, path, JdbcSqlDb::open).also { stores += it }

        // the row survived the rebuild…
        assertEquals(MessageActionStatus.SUCCESS, store.getAction("a1")?.status)
        // …and the new types are accepted, which the old CHECK would have refused
        for (type in listOf(
            MessageActionType.ATT_ACCEPT,
            MessageActionType.ATT_DECLINE,
            MessageActionType.ATT_CANCEL,
            MessageActionType.ATT_ERROR,
        )) {
            store.createAction(action(type.name, messageId = "t1", type = type))
            assertEquals(type, store.getAction(type.name)?.type)
        }

        // running the whole thing again is a no-op rather than a second rebuild
        Schema.apply(db)
        assertEquals(MessageActionStatus.SUCCESS, store.getAction("a1")?.status)
        assertEquals(4, store.getActionsForMessage("t1").size)
    }

    /**
     * There is no attempt budget any more, so a row that only ran out of one was never really
     * refused — it goes back in the queue. One the peer actually answered no to keeps its verdict,
     * because retrying cannot change a refusal.
     */
    @Test
    fun `the migration re-queues budget failures and leaves refusals alone`() {
        scratch.mkdirs()
        val path = File(scratch, "budget.db").path
        val db = JdbcSqlDb.open(path)
        db.execute(
            """
            CREATE TABLE message_actions (
              id TEXT PRIMARY KEY NOT NULL, message_id TEXT NOT NULL, peer_ip TEXT NOT NULL,
              type TEXT NOT NULL CHECK (type IN ('send','react','edit','delete','seen')),
              payload_json TEXT,
              status TEXT NOT NULL CHECK (status IN ('pending','waiting','success','failed','cancelled')),
              attempts INTEGER NOT NULL DEFAULT 0, max_attempts INTEGER NOT NULL DEFAULT 10,
              created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
            """.trimIndent(),
        )
        // gave up after ten silent tries
        db.execute("INSERT INTO message_actions VALUES ('gave-up','m1','fd::1','send',NULL,'failed',10,10,1,1)")
        // the peer said no, and said why
        db.execute(
            "INSERT INTO message_actions VALUES ('refused','m2','fd::1','send'," +
                "'{\"failReason\":\"no-space\"}','failed',1,10,1,1)",
        )
        // and one that was simply finished
        db.execute("INSERT INTO message_actions VALUES ('done','m3','fd::1','send',NULL,'success',1,10,1,1)")

        Schema.apply(db)
        val store = Store(db, path, JdbcSqlDb::open).also { stores += it }

        assertEquals(MessageActionStatus.PENDING, store.getAction("gave-up")?.status)
        assertEquals(MessageActionStatus.FAILED, store.getAction("refused")?.status)
        assertEquals("no-space", store.getAction("refused")?.payload?.failReason)
        assertEquals(MessageActionStatus.SUCCESS, store.getAction("done")?.status)
    }

    /**
     * A parked offer whose transfer row never came across (a backup import does exactly that) can
     * never be renewed, and a waiting head stalls its message's queue forever.
     */
    @Test
    fun `a waiting action with no transfer row is cancelled on open`() {
        val store = store()
        store.insertMessage(message("m1", direction = MessageDirection.OUT))
        store.insertMessage(message("m2", direction = MessageDirection.OUT))
        store.createAction(action("m1", status = MessageActionStatus.WAITING))
        store.createAction(action("m2", status = MessageActionStatus.WAITING))
        store.upsertTransfer("m2", "fd::1", isIncoming = false, state = Wire.TransferState.OFFERED, size = 1)

        Schema.apply(db)

        assertEquals(MessageActionStatus.CANCELLED, store.getAction("m1")?.status)
        assertEquals(MessageActionStatus.WAITING, store.getAction("m2")?.status)
    }

    /**
     * The scheduler's one coarse read. It is the whole input to a pass, so what it leaves out
     * matters as much as what it returns.
     */
    @Test
    fun `the peer queue read groups open work and skips blocked contacts`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.upsertContact("fd::2", "bob")
        store.setContactFlags("fd::2", ContactFlagsPatch(isBlocked = true))

        store.insertMessage(message("m1", direction = MessageDirection.OUT))
        store.insertMessage(message("m2", direction = MessageDirection.OUT))
        store.insertMessage(message("m3", peer = "fd::2", direction = MessageDirection.OUT))
        store.insertMessage(message("m4", peer = "fd::3", direction = MessageDirection.OUT))
        store.insertMessage(message("m5", direction = MessageDirection.OUT))

        store.createAction(action("m1"))
        // parked offers count as open: a peer whose whole queue is waiting must still be probed,
        // or its offers are never renewed and it waits forever
        store.createAction(action("m2", status = MessageActionStatus.WAITING))
        store.createAction(action("m3", peer = "fd::2"))
        // not a contact at all is not blocked, and its messages still have to go somewhere
        store.createAction(action("m4", peer = "fd::3"))
        // finished work is not work
        store.createAction(action("m5", status = MessageActionStatus.SUCCESS))

        val rows = store.peersWithOpenActions(64).associateBy { it.peerIp }
        assertEquals(setOf("fd::1", "fd::3"), rows.keys)
        assertEquals(2, rows.getValue("fd::1").queued)
        assertEquals(1, rows.getValue("fd::3").queued)

        // only the runnable ones are drained; the parked one is not offered to a worker
        assertEquals(listOf("m1"), store.pendingActionsForPeer("fd::1", 100).map { it.id })
        assertEquals(2, store.openActionCount("fd::1"))
        assertEquals(1, store.pendingActionCount("fd::1"))
    }

    /**
     * `created_at` collides constantly — a batch of actions is minted inside one millisecond — and
     * the head of a message's queue must not change between two reads.
     */
    @Test
    fun `actions with the same timestamp keep a stable order`() {
        val store = store()
        store.insertMessage(message("m1", direction = MessageDirection.OUT))
        for (id in listOf("a", "b", "c", "d")) store.createAction(action(id, messageId = "m1"))
        val order = store.getActionsForMessage("m1").map { it.id }
        assertEquals(listOf("a", "b", "c", "d"), order)
        repeat(5) { assertEquals(order, store.getActionsForMessage("m1").map { it.id }) }
    }

    /** A clock that steps backwards must not let a new action overtake one it has to follow. */
    @Test
    fun `a new action is stamped after the ones already queued for its message`() {
        val store = store()
        store.insertMessage(message("m1", direction = MessageDirection.OUT))
        store.createAction(action("first", messageId = "m1").copy(createdAt = 10_000))
        store.createAction(action("second", messageId = "m1").copy(createdAt = 5_000))
        val actions = store.getActionsForMessage("m1")
        assertEquals(listOf("first", "second"), actions.map { it.id })
        assertTrue(actions[1].createdAt > actions[0].createdAt)
    }

    /** Transfers outlived their messages, pinning partial files against the orphan sweep forever. */
    @Test
    fun `clearing a chat and deleting a contact take the transfers with them`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.insertMessage(message("m1", direction = MessageDirection.OUT))
        store.upsertTransfer("m1", "fd::1", isIncoming = false, state = Wire.TransferState.RECEIVING, size = 10)
        assertEquals(listOf("m1"), store.unfinishedTransferIds())

        store.clearMessages("fd::1")
        assertEquals(listOf<String>(), store.unfinishedTransferIds())

        store.insertMessage(message("m2", direction = MessageDirection.OUT))
        store.upsertTransfer("m2", "fd::1", isIncoming = false, state = Wire.TransferState.RECEIVING, size = 10)
        store.deleteContact("fd::1")
        assertEquals(listOf<String>(), store.unfinishedTransferIds())
    }

    @Test
    fun `changing an address moves the transfers too`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.insertMessage(message("m1", direction = MessageDirection.OUT))
        store.upsertTransfer("m1", "fd::1", isIncoming = false, state = Wire.TransferState.OFFERED, size = 10)
        store.changeContactIp("fd::1", "fd::9")
        // without this the parked offer is unrenewable at the new address and stalls its message
        assertEquals(listOf("m1"), store.pendingTransfersForPeer("fd::9", isIncoming = false))
        assertEquals("fd::9", store.transferPeer("m1"))
    }

    /**
     * A type a newer build wrote must stay renderable — and must never be mistaken for a send.
     * The CHECK stops such a row being written here, but a laxer build could have written one, and
     * falling back to SEND would then put a message on the wire that nobody asked for.
     */
    @Test
    fun `an unknown action type decodes to unknown rather than send`() {
        assertEquals(MessageActionType.UNKNOWN, Wire.actionType("something-a-newer-build-writes"))
        assertEquals(MessageActionType.UNKNOWN, Wire.actionType(null))
        // and every type this build knows survives the round trip through the column
        for (type in MessageActionType.entries) {
            if (type == MessageActionType.UNKNOWN) continue
            assertEquals(type, Wire.actionType(with(Wire) { type.wire }))
        }
    }

    @Test
    fun `an action outcome records why it gave up`() {
        val store = store()
        store.insertMessage(message("m1", direction = MessageDirection.OUT))
        store.createAction(action("m1"))
        store.setActionOutcome("m1", MessageActionStatus.FAILED, "no-space")
        val action = store.getAction("m1")
        assertEquals(MessageActionStatus.FAILED, action?.status)
        assertEquals("no-space", action?.payload?.failReason)

        store.resetActionForRetry("m1")
        assertEquals(MessageActionStatus.PENDING, store.getAction("m1")?.status)
        assertEquals(listOf<String>(), store.failedActionIds(null))
    }

    @Test
    fun `traffic and stats add up per peer`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.insertMessage(message("m1", direction = MessageDirection.OUT, body = "hi"))
        store.insertMessage(
            message("m2", ts = 2).copy(kind = MessageKind.IMAGE, attachment = MessageAttachment("a.jpg", "image/jpeg", 5)),
        )
        store.addTraffic("fd::1", 100, 50)
        store.addTraffic("fd::1", 5, 5)

        val stats = store.peerStats("fd::1")
        assertEquals(1, stats.messagesSent)
        assertEquals(1, stats.messagesReceived)
        assertEquals(1, stats.mediaReceived)
        assertEquals(105L, stats.bytesSent)
        assertEquals(55L, stats.bytesReceived)
        assertEquals(1L, stats.firstMessageAt)
        assertEquals(2L, stats.lastActivityAt)
        assertEquals(105L to 55L, store.totalTraffic())
    }

    @Test
    fun `a snapshot copies the live database`() {
        val store = store()
        store.upsertContact("fd::1", "alice")
        store.insertMessage(message("m1", body = "kept"))

        val destination = File(scratch, "snapshot.db")
        store.snapshotTo(destination.path)
        assertTrue(destination.isFile)

        val copy = JdbcSqlDb.open(destination.path)
        val restored = Store(copy, destination.path, JdbcSqlDb::open).also { stores += it }
        assertEquals("kept", restored.getMessage("m1")?.body)
    }

    private fun lastSendStatus(store: Store): MessageActionStatus? =
        store.getChatSummaries().firstOrNull()?.lastSendStatus

    private companion object {
        /** mute_until: 0 = not muted, -1 = muted forever, else epoch ms */
        const val MUTE_FOREVER = -1L
    }
}

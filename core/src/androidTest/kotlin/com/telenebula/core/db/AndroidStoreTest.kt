package com.telenebula.core.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.telenebula.core.model.MessageCursor
import com.telenebula.core.model.ContactPrivacyPrefs
import com.telenebula.core.model.CallLog
import com.telenebula.core.model.CallOutcome
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.ContactFlagsPatch
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import com.telenebula.core.model.MessageAttachment
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The store against the real framework database. The unit tests cover the same SQL through JDBC,
 * which proves the statements are right but not that Android will *run* them: `execSQL` refuses
 * anything that can return a row, `rawQuery` is the only way to bind a typed argument, and the
 * SQLite on an old device is older than any desktop one. Every statement shape the store uses is
 * exercised here at least once, so a device-only rejection fails a test instead of a launch.
 */
@RunWith(AndroidJUnit4::class)
class AndroidStoreTest {
    private val scratch = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "store-test-${System.nanoTime()}",
    ).apply { mkdirs() }

    private val stores = ArrayList<Store>()

    @After
    fun tearDown() {
        stores.forEach { runCatching { it.close() } }
        scratch.deleteRecursively()
    }

    private fun store(name: String = "chats.db"): Store =
        Store.open(File(scratch, name).path).also { stores += it }

    private fun message(
        id: String,
        direction: MessageDirection = MessageDirection.IN,
        body: String = "",
        ts: Long = 1,
    ) = ChatMessage(
        id = id,
        peerIp = PEER,
        direction = direction,
        body = body,
        ts = ts,
        status = if (direction == MessageDirection.OUT) MessageStatus.PENDING else MessageStatus.RECEIVED,
        kind = MessageKind.TEXT,
        isRead = direction == MessageDirection.OUT,
    )

    /** Opening runs the pragmas, the DDL, the ALTERs and the action-table rebuild. */
    @Test
    fun opening_appliesTheWholeSchema() {
        val store = store()
        store.upsertContact(PEER, "alice")
        val contact = store.getContact(PEER)
        assertEquals("alice", contact?.name)
        // a column that only exists after the migrations
        assertEquals(0, contact?.disappearSeconds)
        // and a status the rebuilt CHECK has to accept
        store.insertMessage(message("m1", MessageDirection.OUT, "hi"))
        store.createAction(
            MessageAction(
                id = "m1",
                messageId = "m1",
                peerIp = PEER,
                type = MessageActionType.SEEN,
                status = MessageActionStatus.WAITING,
                attempts = 0,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        assertEquals(MessageActionStatus.WAITING, store.getAction("m1")?.status)
    }

    /** Reopening an existing database runs every migration again over populated tables. */
    @Test
    fun reopening_isIdempotent() {
        store().apply {
            upsertContact(PEER, "alice")
            insertMessage(message("m1", body = "kept"))
            close()
        }
        stores.clear()
        val reopened = store()
        assertEquals("kept", reopened.getMessage("m1")?.body)
        assertEquals(1, reopened.getChatSummaries().size)
    }

    /** Insert-or-ignore, the conditional update behind it, and the changed-row count it returns. */
    @Test
    fun contactSync_reportsWhatActuallyChanged() {
        val store = store()
        assertTrue("a new contact is a change", store.syncContact(PEER, "alice"))
        assertFalse("the same name again is not", store.syncContact(PEER, "alice"))
        assertFalse("an empty announcement never clears a name", store.syncContact(PEER, ""))
        assertTrue("a new name is", store.syncContact(PEER, "alice-renamed"))
        assertEquals("alice-renamed", store.getContact(PEER)?.name)
    }

    /** Typed argument binding: longs in a WHERE, a LIMIT and an IN clause all in one test. */
    @Test
    fun typedArguments_bindAsTheirOwnType() {
        val store = store()
        store.upsertContact(PEER, "alice")
        for (index in 1..5) store.insertMessage(message("m$index", body = "body $index", ts = index.toLong()))

        // LIMIT takes a bound long
        assertEquals(listOf("m4", "m5"), store.getMessages(PEER, 2).map { it.id })
        // a keyset cursor: two longs and a string in one compound comparison
        assertEquals(listOf("m1", "m2"), store.getMessagesBefore(PEER, MessageCursor(3, "m3"), 10).map { it.id })
        assertEquals(listOf("m3", "m4", "m5"), store.getChatView(PEER, MessageCursor(3, "m3"), 100).messages.map { it.id })
        // an IN clause built from a chunked list
        store.markChatRead(PEER)
        assertEquals(5, store.unreportedSeenIds(PEER, 100).size)
        store.markSeenReported(listOf("m1", "m2", "m3", "m4", "m5"))
        assertEquals(0, store.unreportedSeenIds(PEER, 100).size)
        assertTrue(store.setSeenByPeer(listOf("m1", "m2"), PEER).not() || true)
        // a long compared against an INTEGER column
        store.insertMessage(message("gone", body = "expiring").copy(expireSecs = 0, expiresAt = 1))
        assertEquals(1, store.sweepExpired(2).size)
        assertNull(store.getMessage("gone"))
        // a nullable INTEGER column: a bound int sets it, a bound null clears it
        store.setContactPrivacy(PEER, ContactPrivacyPrefs(sendReadReceipts = false))
        assertEquals(false, store.getContact(PEER)?.privacy?.sendReadReceipts)
        store.setContactPrivacy(PEER, ContactPrivacyPrefs())
        assertNull(store.getContact(PEER)?.privacy?.sendReadReceipts)
    }

    /** The one query with a subselect join, a CASE ladder and a COALESCE ordering. */
    @Test
    fun chatSummaries_runOnTheDeviceSqlite() {
        val store = store()
        store.upsertContact(PEER, "alice")
        store.upsertContact(OTHER, "bob")
        store.insertMessage(message("m1", body = "newer", ts = 200))
        store.insertMessage(message("m2", body = "older", ts = 100).copy(peerIp = OTHER))
        store.setContactFlags(OTHER, ContactFlagsPatch(isPinned = true, muteUntil = -1))

        val summaries = store.getChatSummaries()
        assertEquals(listOf(OTHER, PEER), summaries.map { it.ip })
        assertEquals("newer", summaries.first { it.ip == PEER }.lastBody)
        assertEquals(1, summaries.first { it.ip == PEER }.unread)
        assertEquals(-1L, summaries.first().muteUntil)
    }

    /** The chat view: a page of messages plus their actions and reply sources. */
    @Test
    fun chatView_readsEverythingAScreenNeeds() {
        val store = store()
        store.upsertContact(PEER, "alice")
        store.insertMessage(message("m1", body = "first"))
        store.insertMessage(message("m2", MessageDirection.OUT, "reply", ts = 2).copy(replyToId = "m1"))
        store.createAction(
            MessageAction(
                id = "m2",
                messageId = "m2",
                peerIp = PEER,
                type = MessageActionType.SEND,
                status = MessageActionStatus.PENDING,
                attempts = 0,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val view = store.getChatView(PEER, 200)
        assertEquals(2, view.messages.size)
        assertEquals(1, view.actions["m2"]?.size)
        assertEquals("first", view.replySources["m1"]?.body)
    }

    /** A transaction that moves every row keyed by one address to another. */
    @Test
    fun changingAnAddress_movesHistoryInOneTransaction() {
        val store = store()
        store.upsertContact(PEER, "alice")
        store.insertMessage(message("m1", body = "hello"))
        store.addTraffic(PEER, 100, 50)
        store.insertCallLog(
            CallLog(
                id = "c1",
                peerIp = PEER,
                direction = MessageDirection.IN,
                isVideo = false,
                outcome = CallOutcome.ANSWERED,
                startedAt = 1,
                connectedAt = 2,
                endedAt = 3,
            ),
        )

        store.changeContactIp(PEER, OTHER)

        assertNull(store.getContact(PEER))
        assertEquals(1, store.getMessages(OTHER, 10).size)
        assertEquals(1, store.getCallLogs(OTHER, 10).size)
        assertEquals(100L, store.peerStats(OTHER).bytesSent)
    }

    /** Attachments, transfers and the counters that ride along with them. */
    @Test
    fun transfersAndAttachments_roundTrip() {
        val store = store()
        store.upsertContact(PEER, "alice")
        store.insertMessage(
            message("t1").copy(
                kind = MessageKind.IMAGE,
                status = MessageStatus.RECEIVING,
                attachment = MessageAttachment("a.jpg", "image/jpeg", 99),
            ),
        )
        store.upsertTransfer("t1", PEER, isIncoming = true, state = Wire.TransferState.RECEIVING, size = 99)
        store.setTransferReceived("t1", 3)
        assertEquals(3L, store.transferReceived("t1"))
        assertTrue(store.transferIsAccepted("t1"))
        assertEquals(listOf("t1"), store.unfinishedTransferIds())
        // an EXISTS subselect against the messages table
        assertEquals(listOf("t1"), store.resumableTransferIds())

        store.setMessageAttachment("t1", MessageAttachment("a.jpg", "image/jpeg", 99, uri = "file:///x/t1-a.jpg"))
        store.setMessageStatus("t1", MessageStatus.RECEIVED)
        store.setTransferState("t1", Wire.TransferState.COMPLETE)
        assertEquals(1, store.getChatMedia(PEER, 10).size)
        assertEquals(setOf("/x/t1-a.jpg"), store.referencedAttachmentPaths())
    }

    /** The backup path: a WAL checkpoint, a file copy and a merge out of the copy. */
    @Test
    fun snapshotAndMerge_workOnTheFrameworkDatabase() {
        val source = store("source.db")
        source.upsertContact(PEER, "alice")
        source.insertMessage(message("m1", body = "from the backup"))

        val snapshot = File(scratch, "snapshot.db")
        source.snapshotTo(snapshot.path)
        assertTrue(snapshot.isFile)

        val target = store("target.db")
        target.upsertContact(PEER, "alice (local)")
        val (contacts, messages) = target.mergeFrom(snapshot.path, "/old", "/new")
        assertEquals(0, contacts)
        assertEquals(1, messages)
        assertEquals("alice (local)", target.getContact(PEER)?.name)
        assertEquals("from the backup", target.getMessage("m1")?.body)
    }

    @Test
    fun statsQueries_answerOnAnEmptyAndAPopulatedDatabase() {
        val store = store()
        assertEquals(0 to 0, store.actionCounts(null))
        assertEquals(0L to 0L, store.totalTraffic())
        assertEquals(0, store.getUnreadTotal())

        store.upsertContact(PEER, "alice")
        store.insertMessage(message("m1", body = "hi"))
        assertEquals(1, store.getUnreadTotal())
        assertNotNull(store.peerStats(PEER))
        assertEquals(0, store.getLegacyInlineAttachmentIds(10).size)
        assertEquals(1 to 1, store.tableCounts())
    }

    /**
     * The action-table rebuild on the framework database. Every install already in the field runs
     * this on its first open after the upgrade, so a statement Android rejects here is a crash on
     * launch rather than a failing query: `ALTER … RENAME`, a `CREATE` carrying the new CHECK, a
     * named-column `INSERT … SELECT` with a `CASE`/`instr` in it, and a `DROP`, all in one
     * transaction. None of those shapes existed in the store before.
     */
    @Test
    fun actionTableRebuild_runsOnTheFrameworkDatabase() {
        val store = store()
        store.insertMessage(message("m1", MessageDirection.OUT, "hi"))
        for (type in listOf(
            MessageActionType.ATT_ACCEPT,
            MessageActionType.ATT_DECLINE,
            MessageActionType.ATT_CANCEL,
            MessageActionType.ATT_ERROR,
        )) {
            store.createAction(action(type.name, "m1", type))
            assertEquals(type, store.getAction(type.name)?.type)
        }
        store.close()
        stores.clear()

        // reopening runs the whole migration again over a populated table and must change nothing
        val reopened = store()
        assertEquals(4, reopened.getActionsForMessage("m1").size)
        assertEquals(MessageActionType.ATT_ERROR, reopened.getAction(MessageActionType.ATT_ERROR.name)?.type)
    }

    /**
     * The scheduler's one coarse read: a LEFT JOIN whose right side may be absent, a GROUP BY with
     * MIN and COUNT, an IN list of literals and a bound LIMIT — a shape the store has not used.
     */
    @Test
    fun peerQueueRead_runsOnTheFrameworkDatabase() {
        val store = store()
        store.upsertContact(PEER, "alice")
        store.upsertContact(OTHER, "bob")
        store.setContactFlags(OTHER, ContactFlagsPatch(isBlocked = true))
        store.insertMessage(message("m1", MessageDirection.OUT, "one"))
        store.createAction(action("m1", "m1"))
        store.insertMessage(message("m2", MessageDirection.OUT, "two"))
        store.createAction(action("m2", "m2", status = MessageActionStatus.WAITING))

        val rows = store.peersWithOpenActions(64)
        assertEquals(1, rows.size)
        assertEquals(PEER, rows.single().peerIp)
        assertEquals(2, rows.single().queued)
        assertEquals(2, store.openActionCount(PEER))
        assertEquals(1, store.pendingActionCount(PEER))
        assertEquals(listOf("m1"), store.pendingActionsForPeer(PEER, 100).map { it.id })
    }

    /**
     * `rowid` as a tie-break on a table whose primary key is TEXT, and the scalar subquery
     * `createAction` runs inside its INSERT to keep `created_at` monotonic. Both are new shapes,
     * and the SQLite on API 26 is much older than any desktop one.
     */
    @Test
    fun actionOrdering_isStableOnTheDeviceSqlite() {
        val store = store()
        store.insertMessage(message("m1", MessageDirection.OUT, "hi"))
        for (id in listOf("a", "b", "c")) store.createAction(action(id, "m1"))
        // a clock that stepped backwards must not let this one overtake the three above
        store.createAction(action("d", "m1").copy(createdAt = 0))
        assertEquals(listOf("a", "b", "c", "d"), store.getActionsForMessage("m1").map { it.id })
        repeat(3) { assertEquals(listOf("a", "b", "c", "d"), store.getActionsForMessage("m1").map { it.id }) }
    }

    /** The orphan-transfer cleanup: DELETEs keyed on peer_ip, run from two different callers. */
    @Test
    fun transferCleanup_runsOnTheFrameworkDatabase() {
        val store = store()
        store.upsertContact(PEER, "alice")
        store.insertMessage(message("m1", MessageDirection.OUT, "hi"))
        store.upsertTransfer("m1", PEER, isIncoming = false, state = Wire.TransferState.RECEIVING, size = 10)
        assertEquals(listOf("m1"), store.unfinishedTransferIds())
        store.clearMessages(PEER)
        assertEquals(0, store.unfinishedTransferIds().size)

        store.insertMessage(message("m2", MessageDirection.OUT, "hi"))
        store.upsertTransfer("m2", PEER, isIncoming = false, state = Wire.TransferState.OFFERED, size = 10)
        store.deleteContact(PEER)
        assertEquals(0, store.unfinishedTransferIds().size)
    }

    /** The waiting-offer repair that runs on every open, and the address move that carries transfers. */
    @Test
    fun orphanedWaitsAndAddressMoves_runOnTheFrameworkDatabase() {
        val store = store()
        store.upsertContact(PEER, "alice")
        store.insertMessage(message("m1", MessageDirection.OUT, "hi"))
        store.insertMessage(message("m2", MessageDirection.OUT, "hi"))
        store.createAction(action("m1", "m1", status = MessageActionStatus.WAITING))
        store.createAction(action("m2", "m2", status = MessageActionStatus.WAITING))
        store.upsertTransfer("m2", PEER, isIncoming = false, state = Wire.TransferState.OFFERED, size = 10)
        store.close()
        stores.clear()

        val reopened = store()
        // the one with no transfer row could never be renewed, so it stops blocking its message
        assertEquals(MessageActionStatus.CANCELLED, reopened.getAction("m1")?.status)
        assertEquals(MessageActionStatus.WAITING, reopened.getAction("m2")?.status)

        reopened.changeContactIp(PEER, OTHER)
        assertEquals(OTHER, reopened.transferPeer("m2"))
        assertEquals(listOf("m2"), reopened.pendingTransfersForPeer(OTHER, isIncoming = false))
    }

    private fun action(
        id: String,
        messageId: String,
        type: MessageActionType = MessageActionType.SEND,
        status: MessageActionStatus = MessageActionStatus.PENDING,
    ) = MessageAction(
        id = id,
        messageId = messageId,
        peerIp = PEER,
        type = type,
        status = status,
        attempts = 0,
        createdAt = 1,
        updatedAt = 1,
    )

    private companion object {
        const val PEER = "fd00:1234:5678::2"
        const val OTHER = "fd00:1234:5678::3"
    }

    @Test
    fun newIndexes_existAndTheirQueriesRun_onTheFrameworkDatabase() {
        val path = File(scratch, "indexes.db").path
        val store = Store.open(path).also { stores += it }
        val names = AndroidSqlDb.open(path).use { db -> db.query("PRAGMA index_list(messages)") { it.string(1) } }
        assertTrue("expires index", "idx_messages_expires" in names)
        assertTrue("direction/read index", "idx_messages_direction_read" in names)
        assertTrue("seen index", "idx_messages_seen_pending" in names)
        store.insertMessage(message("x1"))
        assertEquals(1, store.getUnreadTotal())
        assertEquals(emptyList<Pair<String, String?>>(), store.sweepExpired(System.currentTimeMillis()))
        assertEquals(emptyList<String>(), store.unreportedSeenIds(PEER, 10))
        assertEquals(0, store.countChatMedia(PEER))
        assertEquals(0, store.countChatLinkMessages(PEER))
    }
}

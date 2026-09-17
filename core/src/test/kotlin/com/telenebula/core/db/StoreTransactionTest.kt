package com.telenebula.core.db

import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/** The batched writes land together or not at all; a failure halfway must leave the rows as they were. */
class StoreTransactionTest {
    private val scratch = File(System.getProperty("java.io.tmpdir"), "tn-tx-${System.nanoTime()}").apply { mkdirs() }
    private val stores = ArrayList<Store>()

    /** Throws on the UPDATE/DELETE numbered [failOnUpdate], counting from one. */
    private class FailingDb(private val inner: SqlDb) : SqlDb {
        var updates = 0
        var failOnUpdate = Int.MAX_VALUE
        override fun execute(sql: String) = inner.execute(sql)
        override fun insert(sql: String, args: List<Any?>): Boolean = inner.insert(sql, args)
        override fun update(sql: String, args: List<Any?>): Int {
            updates += 1
            if (updates == failOnUpdate) throw IllegalStateException("disk full")
            return inner.update(sql, args)
        }
        override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> = inner.query(sql, args, map)
        override fun <T> transaction(block: () -> T): T = inner.transaction(block)
        override fun close() = inner.close()
    }

    @After
    fun tearDown() {
        stores.forEach { runCatching { it.close() } }
        scratch.deleteRecursively()
    }

    private fun store(name: String): Pair<Store, FailingDb> {
        val path = File(scratch, name).path
        val plain = JdbcSqlDb.open(path)
        Schema.apply(plain)
        val failing = FailingDb(plain)
        return Store(failing, path, JdbcSqlDb::open).also { stores += it } to failing
    }

    private fun message(id: String, peerIp: String) = ChatMessage(
        id = id, peerIp = peerIp, direction = MessageDirection.IN, body = "hi", ts = 1L,
        status = MessageStatus.RECEIVED, kind = MessageKind.TEXT, isRead = false,
    )

    @Test
    fun `clearing a chat that fails halfway keeps every row`() {
        val (store, db) = store("clear.db")
        store.upsertContact("fd::1", "a")
        store.insertMessage(message("m1", "fd::1"))
        store.upsertTransfer("m1", "fd::1", isIncoming = true, state = Wire.TransferState.RECEIVING, size = 10)

        // the fourth statement of clearMessages is the DELETE FROM attachment_transfers
        db.failOnUpdate = db.updates + 4
        try {
            store.clearMessages("fd::1")
            fail("expected the failing statement to throw")
        } catch (e: IllegalStateException) {
            assertEquals("disk full", e.message)
        }
        assertNotNull("the message survived the rollback", store.getMessage("m1"))
        assertEquals(Wire.TransferState.RECEIVING, store.transferState("m1"))
    }

    @Test
    fun `marking a chat read that fails halfway leaves it unread`() {
        val (store, db) = store("read.db")
        store.upsertContact("fd::2", "b")
        store.insertMessage(message("m2", "fd::2"))

        // the third statement of markChatRead is the contacts update
        db.failOnUpdate = db.updates + 3
        try {
            store.markChatRead("fd::2")
            fail("expected the failing statement to throw")
        } catch (_: IllegalStateException) {
        }
        assertEquals(1, store.getUnreadTotal())
    }
}

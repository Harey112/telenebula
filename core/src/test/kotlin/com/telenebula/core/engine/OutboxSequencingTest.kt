package com.telenebula.core.engine

import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that keeps a message's actions in order: everything queued behind an action that has
 * not finished waits for it, so an edit can never overtake the send it edits.
 */
class OutboxSequencingTest {
    private fun action(id: String, status: MessageActionStatus, createdAt: Long) = MessageAction(
        id = id,
        messageId = "m1",
        peerIp = "fd::1",
        type = MessageActionType.REACT,
        status = status,
        attempts = 0,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun `the running action is the first unfinished one that is pending`() {
        val actions = listOf(
            action("a", MessageActionStatus.SUCCESS, 1),
            action("b", MessageActionStatus.CANCELLED, 2),
            action("c", MessageActionStatus.PENDING, 3),
            action("d", MessageActionStatus.PENDING, 4),
        )
        assertEquals("c", Outbox.runningActionId(actions))
    }

    @Test
    fun `a failed action stalls the queue until it is retried or cancelled`() {
        val actions = listOf(
            action("a", MessageActionStatus.FAILED, 1),
            action("b", MessageActionStatus.PENDING, 2),
        )
        assertNull(Outbox.runningActionId(actions))
    }

    @Test
    fun `a send parked on an unanswered offer also stalls the queue`() {
        val actions = listOf(
            action("a", MessageActionStatus.WAITING, 1),
            action("b", MessageActionStatus.PENDING, 2),
        )
        assertNull(Outbox.runningActionId(actions))
    }

    @Test
    fun `an empty queue and a finished one both run nothing`() {
        assertNull(Outbox.runningActionId(emptyList()))
        assertNull(Outbox.runningActionId(listOf(action("a", MessageActionStatus.SUCCESS, 1))))
    }

    @Test
    fun `a chunk count covers the whole file and is never zero`() {
        assertEquals(1, TransferManager.chunkCount(1))
        assertEquals(1, TransferManager.chunkCount(Limits.ATT_CHUNK_BYTES.toLong()))
        assertEquals(2, TransferManager.chunkCount(Limits.ATT_CHUNK_BYTES + 1L))
        // an empty file still announces one chunk, which is what the receiver validates against
        assertEquals(1, TransferManager.chunkCount(0))
    }
}

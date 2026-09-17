package com.telenebula.app

import com.telenebula.app.platform.ActionQueue
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Kotlin mirror of the core's `running_action_id` (`native/core/src/outbox.rs`), which decides
 * what a bubble reports as in flight. It is tested against the same cases as the core's own outbox,
 * because a disagreement would make the UI claim something the outbox is not doing.
 *
 * The rule under test: a cancelled action is skipped so the next one proceeds, while a failed one
 * stalls everything behind it until it is retried or cancelled.
 */
class ActionQueueTest {
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
    fun `the running action is the first unfinished one, and only while pending`() {
        val list = listOf(
            action("a", MessageActionStatus.SUCCESS, 1),
            action("b", MessageActionStatus.CANCELLED, 2),
            action("c", MessageActionStatus.PENDING, 3),
            action("d", MessageActionStatus.PENDING, 4),
        )
        assertEquals("c", ActionQueue.runningActionId(list))
    }

    @Test
    fun `a cancelled action lets the next one proceed`() {
        val list = listOf(
            action("a", MessageActionStatus.CANCELLED, 1),
            action("b", MessageActionStatus.PENDING, 2),
        )
        assertEquals("b", ActionQueue.runningActionId(list))
    }

    @Test
    fun `a failed action stalls the queue behind it`() {
        val list = listOf(
            action("a", MessageActionStatus.FAILED, 1),
            action("b", MessageActionStatus.PENDING, 2),
        )
        assertNull(ActionQueue.runningActionId(list))
    }

    /** A send parked on an unanswered offer holds the queue exactly as a failed one does. */
    @Test
    fun `a send waiting on an offer stalls the queue behind it`() {
        val list = listOf(
            action("a", MessageActionStatus.WAITING, 1),
            action("b", MessageActionStatus.PENDING, 2),
        )
        assertNull(ActionQueue.runningActionId(list))
    }

    @Test
    fun `an empty or fully finished queue has nothing running`() {
        assertNull(ActionQueue.runningActionId(emptyList()))
        assertNull(ActionQueue.runningActionId(listOf(action("a", MessageActionStatus.SUCCESS, 1))))
    }
}

package com.telenebula.core.db

import com.telenebula.core.db.Wire.TransferState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferStateTest {
    private val terminal = listOf(TransferState.COMPLETE, TransferState.DECLINED, TransferState.FAILED, TransferState.CANCELLED)

    @Test
    fun `a finished transfer moves nowhere, not even onto itself`() {
        for (state in terminal) for (next in TransferState.entries) {
            assertFalse("$state -> $next", state.canMoveTo(next))
            assertFalse("$state -> $next", state.canApply(next))
        }
    }

    @Test
    fun `the happy path is offered, accepted, receiving, complete`() {
        assertTrue(TransferState.OFFERED.canMoveTo(TransferState.ACCEPTED))
        assertTrue(TransferState.ACCEPTED.canMoveTo(TransferState.RECEIVING))
        assertTrue(TransferState.RECEIVING.canMoveTo(TransferState.COMPLETE))
        assertTrue("a small transfer skips the offer", TransferState.OFFERED.canMoveTo(TransferState.RECEIVING))
    }

    @Test
    fun `a stream never rewinds`() {
        assertFalse(TransferState.RECEIVING.canMoveTo(TransferState.ACCEPTED))
        assertFalse(TransferState.RECEIVING.canMoveTo(TransferState.OFFERED))
        assertFalse(TransferState.ACCEPTED.canMoveTo(TransferState.OFFERED))
        assertFalse("a decline is an answer to an offer, not to a stream", TransferState.ACCEPTED.canMoveTo(TransferState.DECLINED))
    }

    @Test
    fun `only an open state may be re-applied by a redelivered frame`() {
        assertTrue(TransferState.ACCEPTED.canApply(TransferState.ACCEPTED))
        assertTrue(TransferState.OFFERED.canApply(TransferState.ACCEPTED))
        assertFalse(TransferState.RECEIVING.canApply(TransferState.ACCEPTED))
        assertFalse(TransferState.COMPLETE.canApply(TransferState.COMPLETE))
    }
}

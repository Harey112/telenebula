package com.telenebula.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBudgetTest {
    private var now = 0L
    private fun budget() = FrameBudget(perSecond = 10, burst = 5, nowNanos = { now })

    @Test
    fun `a burst is allowed and then refused`() {
        val b = budget()
        assertEquals(5, (1..10).count { b.take() })
    }

    @Test
    fun `tokens come back at the rate`() {
        val b = budget()
        repeat(5) { b.take() }
        assertFalse(b.take())
        now += 100_000_000
        assertTrue(b.take())
        assertFalse(b.take())
    }

    @Test
    fun `a long silence refills only up to the burst`() {
        val b = budget()
        repeat(5) { b.take() }
        now += 60_000_000_000
        assertEquals(5, (1..10).count { b.take() })
    }
}

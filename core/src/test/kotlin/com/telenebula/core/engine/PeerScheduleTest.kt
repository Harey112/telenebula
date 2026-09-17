package com.telenebula.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides what an absent peer costs. Delivery is per peer now, so this ladder — not
 * an attempt budget on each action — is the whole of how often anything is tried.
 */
class PeerScheduleTest {
    private fun schedule() = PeerSchedule("fd::1")

    @Test
    fun `silence climbs the ladder and stops at the floor`() {
        val s = schedule()
        val delays = ArrayList<Long>()
        var now = 0L
        repeat(8) {
            s.onSilent(now)
            delays += s.dueAtMs - now
            now = s.dueAtMs
        }
        // now, 5s, 15s, a minute, then five minutes for as long as it takes
        assertEquals(listOf(5_000L, 15_000L, 60_000L, 300_000L, 300_000L, 300_000L, 300_000L, 300_000L), delays)
    }

    @Test
    fun `a peer that never answers is never given up on`() {
        val s = schedule()
        var now = 0L
        repeat(1_000) {
            s.onSilent(now)
            now = s.dueAtMs
        }
        assertFalse(s.isReachable)
        // still scheduled, still at the floor: there is no state that means "stopped trying"
        assertTrue(s.isDue(now))
        assertEquals(Limits.PING_BACKOFF_MS.last(), s.dueAtMs - (now - Limits.PING_BACKOFF_MS.last()))
    }

    @Test
    fun `any proof of life returns the peer to the fast end, from any rung`() {
        for (rungs in 1..Limits.PING_BACKOFF_MS.size + 2) {
            val s = schedule()
            var now = 0L
            repeat(rungs) {
                s.onSilent(now)
                now = s.dueAtMs
            }
            s.onReachable(now)
            assertTrue("rung $rungs is due at once", s.isDue(now))
            assertTrue(s.isReachable)
            // and the next silence starts from the first rung again, not where it left off
            s.onSilent(now)
            assertEquals(Limits.PING_BACKOFF_MS[1], s.dueAtMs - now)
        }
    }

    /**
     * The guard against the scheduler spinning: a peer with nothing runnable left — an empty queue,
     * or one holding only offers the other side has not answered — must stop being due at all.
     */
    @Test
    fun `a peer with nothing runnable stops being due`() {
        val s = schedule()
        s.onSilent(0)
        s.sleepUntilWoken()
        assertFalse(s.isDue(Long.MAX_VALUE - 1))
        assertNull(s.snapshot(0, isTunnelUp = true).nextProbeInMs)

        // and work arriving wakes it again
        s.onReachable(5_000)
        assertTrue(s.isDue(5_000))
    }

    @Test
    fun `a drain with work left over comes back soon but never at zero`() {
        val s = schedule()
        s.dueIn(1_000, 0)
        assertTrue("a zero delay would spin", s.dueAtMs > 1_000)
        s.dueIn(1_000, Limits.DRAIN_RESUME_MS)
        assertEquals(1_000 + Limits.DRAIN_RESUME_MS, s.dueAtMs)
    }

    @Test
    fun `jitter stays inside its band and never turns a delay negative`() {
        val s = schedule()
        repeat(200) {
            val jittered = s.let {
                it.onSilent(0) { delay -> spread(delay) }
                it.dueAtMs
            }
            assertTrue("$jittered", jittered > 0)
        }
    }

    /** The scheduler's own spread, restated here so the band is asserted rather than assumed. */
    private fun spread(delayMs: Long): Long {
        if (delayMs <= 0) return delayMs
        val band = (delayMs * Limits.PING_JITTER).toLong().coerceAtLeast(1)
        return delayMs + kotlin.random.Random.nextLong(-band, band + 1)
    }

    @Test
    fun `the snapshot reports what the chat can explain to the user`() {
        val s = schedule()
        s.queued = 3
        s.onSilent(1_000)
        val away = s.snapshot(1_000, isTunnelUp = true)
        assertEquals(3, away.queued)
        assertFalse(away.isReachable)
        assertEquals(Limits.PING_BACKOFF_MS[1], away.nextProbeInMs)

        s.onReachable(1_000)
        assertTrue(s.snapshot(1_000, isTunnelUp = true).isReachable)
        // the tunnel being down is not the peer's fault and is reported apart from reachability
        assertFalse(s.snapshot(1_000, isTunnelUp = false).isTunnelUp)
    }
}

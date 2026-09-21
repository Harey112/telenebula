package com.telenebula.dex.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginThrottleTest {
    private var clock = 5_000L

    @Test
    fun `five failures lock the address for the lock window, then it may try again`() {
        val throttle = LoginThrottle({ clock }, failsBeforeLock = 5, lockMs = 60_000)
        repeat(4) { throttle.recordFailure("1.2.3.4") }
        assertEquals(0L, throttle.lockedFor("1.2.3.4"))
        throttle.recordFailure("1.2.3.4")
        assertEquals(60_000L, throttle.lockedFor("1.2.3.4"))
        assertEquals(0L, throttle.lockedFor("5.6.7.8"))
        clock += 59_999
        assertEquals(1L, throttle.lockedFor("1.2.3.4"))
        clock += 1
        assertEquals(0L, throttle.lockedFor("1.2.3.4"))
    }

    @Test
    fun `a success clears the count`() {
        val throttle = LoginThrottle({ clock }, failsBeforeLock = 2, lockMs = 1_000)
        throttle.recordFailure("a")
        throttle.clear("a")
        throttle.recordFailure("a")
        assertEquals(0L, throttle.lockedFor("a"))
    }

    @Test
    fun `stale entries are purged and the table is bounded`() {
        val throttle = LoginThrottle({ clock }, failsBeforeLock = 2, lockMs = 1_000, capacity = 2)
        throttle.recordFailure("a")
        throttle.recordFailure("b")
        throttle.recordFailure("c")
        throttle.recordFailure("c")
        assertTrue(throttle.lockedFor("c") > 0)
        clock += 5_000
        assertEquals(0L, throttle.lockedFor("c"))
    }
}

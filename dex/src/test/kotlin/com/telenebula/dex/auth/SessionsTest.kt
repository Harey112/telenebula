package com.telenebula.dex.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsTest {
    private var clock = 1_000_000L

    @Test
    fun `a created session is found until it idles out, and touching it slides the expiry`() {
        val sessions = Sessions({ clock }, capacity = 4, idleMs = 1_000)
        val token = sessions.create("harey", "192.168.1.2").token
        assertEquals(43, token.length)
        clock += 900
        assertNotNull(sessions.find(token))
        clock += 900
        assertNotNull(sessions.find(token))
        clock += 1_001
        assertNull(sessions.find(token))
        assertEquals(0, sessions.size)
    }

    @Test
    fun `unknown, empty and oversized tokens are not sessions`() {
        val sessions = Sessions({ clock })
        sessions.create("harey", "a")
        assertNull(sessions.find(null))
        assertNull(sessions.find(""))
        assertNull(sessions.find("nope"))
        assertNull(sessions.find("x".repeat(65)))
    }

    @Test
    fun `past capacity the oldest session is evicted`() {
        val sessions = Sessions({ clock }, capacity = 2, idleMs = 100_000)
        val first = sessions.create("u", "a").token
        clock += 1
        val second = sessions.create("u", "a").token
        clock += 1
        val third = sessions.create("u", "a").token
        assertNull(sessions.find(first))
        assertNotNull(sessions.find(second))
        assertNotNull(sessions.find(third))
        assertEquals(2, sessions.size)
    }

    @Test
    fun `remove and clear forget sessions`() {
        val sessions = Sessions({ clock })
        val token = sessions.create("u", "a").token
        assertTrue(sessions.remove(token))
        assertFalse(sessions.remove(token))
        sessions.create("u", "a")
        sessions.clear()
        assertEquals(0, sessions.size)
    }
}

package com.telenebula.dex.http

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAdmissionTest {
    private fun ip(text: String): InetAddress = InetAddress.getByName(text)
    private var clock = 1_000L

    @Test
    fun `a connection that reached one of the phone's LAN addresses is admitted, from wherever it was routed`() {
        val admission = LanAdmission({ listOf(ip("192.168.1.5"), ip("fd12:3456::5")) }, { clock })
        assertTrue(admission.admits(ip("192.168.1.5")))
        assertTrue(admission.admits(ip("::ffff:192.168.1.5")))
        assertTrue(admission.admits(ip("fd12:3456::5")))
    }

    @Test
    fun `one that reached the overlay, cellular or loopback address is not`() {
        val admission = LanAdmission({ listOf(ip("192.168.1.5")) }, { clock })
        assertFalse(admission.admits(ip("fd00:42::1")))
        assertFalse(admission.admits(ip("100.64.0.3")))
        assertFalse(admission.admits(ip("127.0.0.1")))
    }

    @Test
    fun `a link that comes up is seen on the next miss, re-read at most once per window`() {
        val reads = AtomicInteger(0)
        var addresses = listOf(ip("192.168.1.5"))
        val admission = LanAdmission({ reads.incrementAndGet(); addresses }, { clock }, refreshMs = 1_000)
        assertTrue(admission.admits(ip("192.168.1.5")))
        assertEquals(1, reads.get())
        addresses = addresses + ip("192.168.43.1")
        assertFalse(admission.admits(ip("192.168.43.1")))
        assertFalse(admission.admits(ip("192.168.43.1")))
        assertEquals(1, reads.get())
        clock += 1_000
        assertTrue(admission.admits(ip("192.168.43.1")))
        assertEquals(2, reads.get())
        addresses = emptyList()
        clock += 1_000
        assertFalse(admission.admits(ip("10.9.9.9")))
        assertFalse(admission.admits(ip("192.168.1.5")))
    }
}

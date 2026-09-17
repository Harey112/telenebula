package com.telenebula.app.platform

import com.telenebula.app.model.CertExpiryLevel
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CertInspectorTest {
    private val now = Instant.parse("2026-09-16T12:00:00Z").toEpochMilli()
    private val day = 86_400_000L

    private fun status(daysFromNow: Long) = CertInspector.expiryStatus(Instant.ofEpochMilli(now + daysFromNow * day).toString(), now)

    @Test
    fun `the boundaries of the warning window`() {
        assertEquals(CertExpiryLevel.EXPIRED, status(-1).level)
        assertEquals(CertExpiryLevel.WARNING, status(0).level)
        assertEquals("Expires today", status(0).text)
        assertEquals(CertExpiryLevel.WARNING, status(1).level)
        assertTrue(status(1).text.startsWith("Expires in 1 day ("))
        assertEquals(CertExpiryLevel.WARNING, status(30).level)
        assertTrue(status(30).text.startsWith("Expires in 30 days ("))
        assertEquals(CertExpiryLevel.OK, status(31).level)
        assertTrue(status(31).text.startsWith("Valid until "))
    }

    @Test
    fun `an unparseable date is a warning, not a crash`() {
        val status = CertInspector.expiryStatus("someday", now)
        assertEquals(CertExpiryLevel.WARNING, status.level)
        assertEquals("Expiry date unknown", status.text)
    }
}

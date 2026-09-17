package com.telenebula.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test
    fun `dotted versions compare numerically, not lexically`() {
        assertTrue(compareVersions("1.10.0", "1.9.0") > 0)
        assertTrue(compareVersions("1.9.0", "1.10.0") < 0)
        assertEquals(0, compareVersions("2.0", "2.0.0"))
        assertTrue(compareVersions("2.0.1", "2.0") > 0)
    }

    @Test
    fun `non numeric parts count as zero`() {
        assertEquals(0, compareVersions("1.0.0-beta", "1.0.0"))
        assertTrue(compareVersions("1.0.1", "1.0.0-rc1") > 0)
    }

    @Test
    fun `a failure has a message a person can read`() {
        assertEquals("disk full", IllegalStateException("disk full").userMessage())
        assertEquals("IllegalStateException", IllegalStateException("   ").userMessage())
        assertEquals("NullPointerException", NullPointerException().userMessage())
    }
}

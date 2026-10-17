package com.telenebula.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadsTest {
    @Test
    fun `a code carries the fingerprint to the scanner`() {
        val decoded = QrPayloads.decode(QrPayloads.encode("alice-phone", "fd00::2", "ab12cd"))
        assertEquals("alice-phone", decoded?.name)
        assertEquals("fd00::2", decoded?.ip)
        assertEquals("ab12cd", decoded?.fp)
    }

    @Test
    fun `a code from an older build still decodes, with no fingerprint`() {
        val decoded = QrPayloads.decode("""{"app":"telenebula","name":"bob","ip":"fd00::3"}""")
        assertEquals("fd00::3", decoded?.ip)
        assertEquals("", decoded?.fp)
    }

    @Test
    fun `something else is not a contact`() {
        assertNull(QrPayloads.decode("https://example.com"))
    }
}

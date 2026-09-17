package com.telenebula.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class LogRedactionTest {
    @Test
    fun `a pem block is replaced and the surrounding log survives`() {
        val log = "before\n-----BEGIN NEBULA X25519 PRIVATE KEY-----\nabc\ndef\n-----END NEBULA X25519 PRIVATE KEY-----\nafter"
        assertEquals("before\n[redacted]\nafter", LogRedaction.redact(log))
    }

    @Test
    fun `a log without keys is untouched`() {
        val log = "time=1 level=info msg=\"Firewall rule added\""
        assertEquals(log, LogRedaction.redact(log))
    }
}

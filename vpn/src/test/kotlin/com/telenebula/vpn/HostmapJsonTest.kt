package com.telenebula.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two field-name generations mobile_nebula has shipped; a shift here empties the Diagnostics peer list silently. */
class HostmapJsonTest {
    private val legacy = """
        [{"VpnIp":"FD10:100::107","RemoteAddrs":["203.0.113.5:4242"],"CurrentRemote":"203.0.113.5:4242",
          "Cert":{"details":{"name":"bob","fingerprint":"abc"}},"RelayState":null}]
    """.trimIndent()

    private val current = """
        [{"vpnAddrs":["fd10:100::108"],"remoteAddrs":[],"currentRemote":null,
          "cert":{"Details":{"Name":"carol"},"Fingerprint":"def"},"relayState":{"relays":["fd10:100::1"]}},
         {"unrelated":true}]
    """.trimIndent()

    @Test
    fun `the legacy shape parses with a direct remote and lowercase address`() {
        val entries = HostmapJson.parseList(legacy, lighthouseSet(listOf("FD10:100::1")))
        val e = entries.single()
        assertEquals("fd10:100::107", e.vpnIp)
        assertEquals("203.0.113.5:4242", e.currentRemote)
        assertEquals(listOf("203.0.113.5:4242"), e.remoteAddrs)
        assertEquals("bob", e.certName)
        assertEquals("abc", e.certFingerprint)
        assertFalse(e.isRelayed)
        assertFalse(e.isLighthouse)
    }

    @Test
    fun `the current shape parses, a relayed peer is marked, and junk entries are skipped`() {
        val entries = HostmapJson.parseList(current, lighthouseSet(listOf("fd10:100::108")))
        val e = entries.single()
        assertEquals("fd10:100::108", e.vpnIp)
        assertNull(e.currentRemote)
        assertEquals("carol", e.certName)
        assertEquals("def", e.certFingerprint)
        assertTrue(e.isRelayed)
        assertTrue(e.isLighthouse)
    }

    @Test
    fun `garbage is an empty list, never an exception`() {
        assertTrue(HostmapJson.parseList("not json", emptySet()).isEmpty())
        assertTrue(HostmapJson.parseList("{}", emptySet()).isEmpty())
        assertNull(HostmapJson.parseOne("[]", emptySet()))
    }
}

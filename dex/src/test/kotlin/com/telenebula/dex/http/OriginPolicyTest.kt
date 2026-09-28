package com.telenebula.dex.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginPolicyTest {
    private fun request(vararg headers: String): HttpRequest =
        HttpParser.parse("GET /ws HTTP/1.1\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n")

    @Test
    fun `the address forms the phone shows parse with their effective port`() {
        assertEquals(Authority("192.168.1.5", 8420), OriginPolicy.parseAuthority("192.168.1.5:8420"))
        assertEquals(Authority("[fd00::1:5]", 8420), OriginPolicy.parseAuthority("[FD00::1:5]:8420"))
        assertEquals(Authority("[fe80::1]", 443), OriginPolicy.parseAuthority("[fe80::1]"))
        assertEquals(Authority("phone.local", 8420), OriginPolicy.parseAuthority("Phone.Local:8420"))
        assertEquals(Authority("phone.local", 443), OriginPolicy.parseAuthority("phone.local"))
        assertEquals("phone.local", Authority("phone.local", 443).text)
        assertEquals("[fd00::5]:8420", Authority("[fd00::5]", 8420).text)
    }

    @Test
    fun `malformed hosts are refused`() {
        for (bad in listOf("", ":8420", "192.168.1.5:", "192.168.1.5:0", "192.168.1.5:65536", "192.168.1.5:+80", "192.168.1.256", "192.168.1", "01.2.3.4",
            "[fd00::1", "fd00::1", "[fd00::1]x", "[fd00::1%25wlan0]", "[not-v6]", "evil.example:8420/x", "a b", "user@host", "-bad.example", "x..y", "a_b.example")) {
            assertNull("accepted '$bad'", OriginPolicy.parseAuthority(bad))
        }
    }

    @Test
    fun `a request must name exactly one host, on the port it reached`() {
        assertEquals(Authority("192.168.1.5", 8420), OriginPolicy.authorityOf(request("Host: 192.168.1.5:8420"), 8420))
        assertNull(OriginPolicy.authorityOf(request("Host: 192.168.1.5:9999"), 8420))
        assertNull(OriginPolicy.authorityOf(request("Host: 192.168.1.5"), 8420))
        assertNull(OriginPolicy.authorityOf(request(), 8420))
        assertNull(OriginPolicy.authorityOf(request("Host: 192.168.1.5:8420", "Host: evil.example:8420"), 8420))
        assertNull(OriginPolicy.authorityOf(request("Host: 192.168.1.5:8420, evil.example:8420"), 8420))
    }

    @Test
    fun `an upgrade needs the exact https origin, port included`() {
        val authority = Authority("192.168.1.5", 8420)
        fun upgrade(vararg headers: String) = OriginPolicy.mayUpgrade(request("Host: 192.168.1.5:8420", *headers), authority)
        assertTrue(upgrade("Origin: https://192.168.1.5:8420"))
        assertTrue(upgrade("Origin: https://192.168.1.5:8420", "Sec-Fetch-Site: same-origin"))
        assertFalse(upgrade())
        assertFalse(upgrade("Origin: null"))
        assertFalse(upgrade("Origin: http://192.168.1.5:8420"))
        assertFalse(upgrade("Origin: https://192.168.1.5"))
        assertFalse(upgrade("Origin: https://192.168.1.5:8421"))
        assertFalse(upgrade("Origin: https://192.168.1.6:8420"))
        assertFalse(upgrade("Origin: https://192.168.1.5:8420/"))
        assertFalse(upgrade("Origin: https://evil.example"))
        assertFalse(upgrade("Origin: https://192.168.1.5:8420", "Origin: https://192.168.1.5:8420"))
        assertFalse(upgrade("Origin: https://192.168.1.5:8420", "Sec-Fetch-Site: cross-site"))
        assertFalse(upgrade("Origin: https://192.168.1.5:8420", "Sec-Fetch-Site: none"))
        val v6 = Authority("[fd00::5]", 8420)
        assertTrue(OriginPolicy.mayUpgrade(request("Origin: https://[fd00::5]:8420"), v6))
        val standard = Authority("phone.local", 443)
        assertTrue(OriginPolicy.mayUpgrade(request("Origin: https://phone.local"), standard))
        assertTrue(OriginPolicy.mayUpgrade(request("Origin: https://phone.local:443"), standard))
    }

    @Test
    fun `a cookie POST needs its own origin, or same-origin fetch metadata where Origin is absent`() {
        val authority = Authority("192.168.1.5", 8420)
        fun act(vararg headers: String) = OriginPolicy.mayAct(request(*headers), authority)
        assertTrue(act("Origin: https://192.168.1.5:8420"))
        assertTrue(act("Sec-Fetch-Site: same-origin"))
        assertFalse(act())
        assertFalse(act("Sec-Fetch-Site: cross-site"))
        assertFalse(act("Sec-Fetch-Site: same-site"))
        assertFalse(act("Sec-Fetch-Site: none"))
        assertFalse(act("Origin: https://evil.example", "Sec-Fetch-Site: same-origin"))
        assertFalse(act("Origin: https://192.168.1.5:8420", "Sec-Fetch-Site: cross-site"))
    }

    @Test
    fun `a cookie read is refused only when another site asked for it`() {
        val authority = Authority("192.168.1.5", 8420)
        fun read(vararg headers: String) = OriginPolicy.mayRead(request(*headers), authority)
        assertTrue(read())
        assertTrue(read("Sec-Fetch-Site: same-origin"))
        assertTrue(read("Sec-Fetch-Site: none"))
        assertFalse(read("Sec-Fetch-Site: cross-site"))
        assertFalse(read("Sec-Fetch-Site: same-site"))
        assertFalse(read("Origin: https://evil.example"))
    }
}

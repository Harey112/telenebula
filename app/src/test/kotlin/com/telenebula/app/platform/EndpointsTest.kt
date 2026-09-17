package com.telenebula.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointsTest {
    @Test
    fun `a stored endpoint splits into host and port and joins back unchanged`() {
        val v4 = Endpoints.split("47.104.245.138:4242")
        assertEquals("47.104.245.138", v4.host)
        assertEquals("4242", v4.port)
        assertEquals("47.104.245.138:4242", Endpoints.join(v4.host, v4.port))

        val name = Endpoints.split("lh2.example.com:4242")
        assertEquals("lh2.example.com", name.host)
        assertEquals("4242", name.port)

        val v6 = Endpoints.split("[2001:db8::7]:4242")
        assertEquals("2001:db8::7", v6.host)
        assertEquals("4242", v6.port)
        assertEquals("[2001:db8::7]:4242", Endpoints.join(v6.host, v6.port))
    }

    @Test
    fun `a value without a port keeps its text as the host`() {
        val bare = Endpoints.split("2001:db8::7")
        assertEquals("2001:db8::7", bare.host)
        assertEquals("", bare.port)
        assertEquals("", Endpoints.split("lighthouse").port)
    }

    @Test
    fun `validity is a host and a port in range`() {
        assertTrue(Endpoints.isValid("203.0.113.10", "4242"))
        assertTrue(Endpoints.isValid("lh.example.com", " 65535 "))
        assertFalse(Endpoints.isValid("", "4242"))
        assertFalse(Endpoints.isValid("203.0.113.10", "0"))
        assertFalse(Endpoints.isValid("203.0.113.10", "70000"))
        assertFalse(Endpoints.isValid("203.0.113.10", "port"))
        assertFalse(Endpoints.isValid("two words", "4242"))
    }
}

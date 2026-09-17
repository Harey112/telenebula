package com.telenebula.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class VpnStartHandoffTest {
    private fun request(key: String = "k") = VpnStartRequest("{}", key, listOf("10.0.0.1/24"), emptyList(), 1300)

    @Test
    fun `a request is taken once and then gone`() {
        val offered = request()
        val nonce = VpnStartHandoff.offer(offered)
        assertSame(offered, VpnStartHandoff.take(nonce))
        assertNull(VpnStartHandoff.take(nonce))
    }

    @Test
    fun `a wrong or missing nonce takes nothing and leaves the request in place`() {
        val offered = request()
        val nonce = VpnStartHandoff.offer(offered)
        assertNull(VpnStartHandoff.take("stale"))
        assertNull(VpnStartHandoff.take(null))
        assertSame(offered, VpnStartHandoff.take(nonce))
    }

    @Test
    fun `a newer offer supersedes the older one`() {
        val first = VpnStartHandoff.offer(request("first"))
        val second = VpnStartHandoff.offer(request("second"))
        assertNotEquals(first, second)
        assertNull(VpnStartHandoff.take(first))
        assertEquals("second", VpnStartHandoff.take(second)?.key)
    }

    @Test
    fun `clear drops a pending request`() {
        val nonce = VpnStartHandoff.offer(request())
        VpnStartHandoff.clear()
        assertNull(VpnStartHandoff.take(nonce))
    }
}

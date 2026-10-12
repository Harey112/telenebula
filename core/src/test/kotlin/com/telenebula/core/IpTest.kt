package com.telenebula.core

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpTest {
    private fun addr(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun `a mapped IPv4 address unwraps to the same address`() {
        val mapped = InetAddress.getByAddress(ByteArray(10) + byteArrayOf(-1, -1, 10, 42, 0, 7))
        assertEquals(addr("10.42.0.7"), Ip.unmapped(mapped))
        assertEquals(addr("fd00::7"), Ip.unmapped(addr("fd00::7")))
    }

    @Test
    fun `membership follows the prefix bits`() {
        assertTrue(Ip.isInCidr(addr("fd00:1234:5678::9"), "fd00:1234:5678::2/64"))
        assertFalse(Ip.isInCidr(addr("fd00:1234:5679::9"), "fd00:1234:5678::2/64"))
        assertTrue(Ip.isInCidr(addr("10.42.0.200"), "10.42.0.7/24"))
        assertFalse(Ip.isInCidr(addr("10.42.1.1"), "10.42.0.7/24"))
        assertTrue(Ip.isInCidr(addr("10.42.0.7"), "10.42.0.6/31"))
        assertFalse(Ip.isInCidr(addr("10.42.0.8"), "10.42.0.6/31"))
    }

    @Test
    fun `a mapped sender is measured as IPv4`() {
        val mapped = InetAddress.getByAddress(ByteArray(10) + byteArrayOf(-1, -1, 10, 42, 0, 9))
        assertTrue(Ip.isInCidr(mapped, "10.42.0.0/24"))
    }

    @Test
    fun `families never match each other`() {
        assertFalse(Ip.isInCidr(addr("10.42.0.7"), "fd00::/8"))
        assertFalse(Ip.isInCidr(addr("fd00::7"), "10.0.0.0/8"))
    }

    @Test
    fun `a malformed or named network matches nothing`() {
        assertFalse(Ip.isInCidr(addr("10.42.0.7"), "10.42.0.0"))
        assertFalse(Ip.isInCidr(addr("10.42.0.7"), "10.42.0.0/33"))
        assertFalse(Ip.isInCidr(addr("127.0.0.1"), "localhost/8"))
        assertFalse(Ip.isInCidr(addr("10.42.0.7"), ""))
    }
}

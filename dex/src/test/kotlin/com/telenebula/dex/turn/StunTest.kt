package com.telenebula.dex.turn

import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StunTest {
    private val txid = ByteArray(12) { (it + 1).toByte() }

    @Test
    fun `type field interleaves method and class as the RFC lays them out`() {
        assertEquals(0x0001, Stun.typeOf(Stun.METHOD_BINDING, Stun.CLASS_REQUEST))
        assertEquals(0x0101, Stun.typeOf(Stun.METHOD_BINDING, Stun.CLASS_SUCCESS))
        assertEquals(0x0111, Stun.typeOf(Stun.METHOD_BINDING, Stun.CLASS_ERROR))
        assertEquals(0x0003, Stun.typeOf(Stun.METHOD_ALLOCATE, Stun.CLASS_REQUEST))
        assertEquals(0x0113, Stun.typeOf(Stun.METHOD_ALLOCATE, Stun.CLASS_ERROR))
        assertEquals(0x0016, Stun.typeOf(Stun.METHOD_SEND, Stun.CLASS_INDICATION))
        assertEquals(0x0017, Stun.typeOf(Stun.METHOD_DATA, Stun.CLASS_INDICATION))
        assertEquals(0x0109, Stun.typeOf(Stun.METHOD_CHANNEL_BIND, Stun.CLASS_SUCCESS))
        for (method in listOf(Stun.METHOD_BINDING, Stun.METHOD_ALLOCATE, Stun.METHOD_REFRESH, Stun.METHOD_SEND, Stun.METHOD_DATA, Stun.METHOD_CREATE_PERMISSION, Stun.METHOD_CHANNEL_BIND)) {
            for (cls in 0..3) {
                val type = Stun.typeOf(method, cls)
                assertEquals(method, Stun.methodOf(type))
                assertEquals(cls, Stun.classOf(type))
            }
        }
    }

    @Test
    fun `a built message parses back with its attributes and passes its own integrity check`() {
        val key = Stun.longTermKey("user", "realm", "pass")
        val bytes = Stun.Builder(Stun.METHOD_ALLOCATE, Stun.CLASS_REQUEST, txid)
            .addInt(Stun.ATTR_REQUESTED_TRANSPORT, Stun.TRANSPORT_UDP shl 24)
            .addString(Stun.ATTR_USERNAME, "user")
            .addString(Stun.ATTR_REALM, "realm")
            .addString(Stun.ATTR_NONCE, "abc")
            .build(key)
        val parsed = Stun.parse(bytes, bytes.size)
        assertNotNull(parsed)
        assertEquals(Stun.METHOD_ALLOCATE, parsed?.method)
        assertEquals(Stun.CLASS_REQUEST, parsed?.cls)
        assertArrayEquals(txid, parsed?.transactionId)
        assertEquals("user", parsed?.first(Stun.ATTR_USERNAME)?.toString(Charsets.UTF_8))
        assertEquals("abc", parsed?.first(Stun.ATTR_NONCE)?.toString(Charsets.UTF_8))
        assertEquals(20, parsed?.first(Stun.ATTR_MESSAGE_INTEGRITY)?.size)
        assertEquals(4, parsed?.first(Stun.ATTR_FINGERPRINT)?.size)
        assertTrue(Stun.verifyIntegrity(bytes, bytes.size, key))
        assertFalse(Stun.verifyIntegrity(bytes, bytes.size, Stun.longTermKey("user", "realm", "wrong")))
    }

    @Test
    fun `a tampered message fails integrity and a message without it fails too`() {
        val key = Stun.longTermKey("u", "r", "p")
        val bytes = Stun.Builder(Stun.METHOD_REFRESH, Stun.CLASS_REQUEST, txid).addInt(Stun.ATTR_LIFETIME, 600).build(key)
        val tampered = bytes.copyOf()
        tampered[Stun.HEADER_BYTES + 5] = (tampered[Stun.HEADER_BYTES + 5] + 1).toByte()
        assertFalse(Stun.verifyIntegrity(tampered, tampered.size, key))
        val plain = Stun.Builder(Stun.METHOD_REFRESH, Stun.CLASS_REQUEST, txid).build()
        assertFalse(Stun.verifyIntegrity(plain, plain.size, key))
    }

    @Test
    fun `garbage and truncated input parse to null`() {
        assertNull(Stun.parse(ByteArray(10), 10))
        val good = Stun.Builder(Stun.METHOD_BINDING, Stun.CLASS_REQUEST, txid).software().build()
        assertNull(Stun.parse(good, good.size - 1))
        val badCookie = good.copyOf().also { it[4] = 0 }
        assertNull(Stun.parse(badCookie, badCookie.size))
        val channelData = good.copyOf().also { it[0] = 0x40 }
        assertNull(Stun.parse(channelData, channelData.size))
        val badLength = good.copyOf().also { it[3] = (it[3] + 4).toByte() }
        assertNull(Stun.parse(badLength, badLength.size))
    }

    @Test
    fun `xor addresses round trip for both families and reject the malformed`() {
        val v4 = InetSocketAddress(InetAddress.getByName("192.168.1.20"), 51234)
        val v6 = InetSocketAddress(InetAddress.getByName("fd00::1234"), 3478)
        assertEquals(v4, Stun.decodeXorAddress(Stun.encodeXorAddress(v4, txid), txid))
        assertEquals(v6, Stun.decodeXorAddress(Stun.encodeXorAddress(v6, txid), txid))
        assertNull(Stun.decodeXorAddress(ByteArray(3), txid))
        assertNull(Stun.decodeXorAddress(ByteArray(8).also { it[1] = 0x07 }, txid))
        assertNull(Stun.decodeXorAddress(ByteArray(9).also { it[1] = 0x01 }, txid))
    }

    @Test
    fun `error code splits into class and number`() {
        val bytes = Stun.Builder(Stun.METHOD_ALLOCATE, Stun.CLASS_ERROR, txid).errorCode(437, "Allocation Mismatch").build()
        val value = Stun.parse(bytes, bytes.size)?.first(Stun.ATTR_ERROR_CODE)
        assertNotNull(value)
        assertEquals(4, value?.get(2)?.toInt())
        assertEquals(37, value?.get(3)?.toInt())
        assertEquals("Allocation Mismatch", value?.copyOfRange(4, value.size)?.toString(Charsets.UTF_8))
    }

    @Test
    fun `channel data frames the payload and the first byte tells it from stun`() {
        val frame = Stun.channelData(0x4000, byteArrayOf(1, 2, 3), 0, 3)
        assertEquals(7, frame.size)
        assertTrue(Stun.isChannelData(frame[0]))
        assertFalse(Stun.isStun(frame[0]))
        assertEquals(3, frame[3].toInt())
        assertTrue(Stun.isChannelNumber(0x4000))
        assertTrue(Stun.isChannelNumber(0x7FFF))
        assertFalse(Stun.isChannelNumber(0x3FFF))
        assertFalse(Stun.isChannelNumber(0x8000))
    }
}

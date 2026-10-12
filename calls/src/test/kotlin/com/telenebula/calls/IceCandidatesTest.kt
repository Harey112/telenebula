package com.telenebula.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IceCandidatesTest {
    @Test
    fun readsAddressAndType() {
        val line = "candidate:1 1 udp 2130706431 fd10:100::7 50000 typ host generation 0"
        assertEquals("fd10:100::7", IceCandidates.address(line))
        assertEquals("host", IceCandidates.type(line))
    }

    @Test
    fun matchesTheSameAddressWrittenDifferently() {
        assertTrue(IceCandidates.isOnAddress("candidate:1 1 udp 1 fd10:100:0:0:0:0:0:7 5 typ host", "fd10:100::7"))
        assertTrue(IceCandidates.isOnAddress("candidate:1 1 udp 1 10.42.0.7 5 typ host", "10.42.0.7"))
    }

    @Test
    fun refusesOtherAddresses() {
        assertFalse(IceCandidates.isOnAddress("candidate:1 1 udp 1 192.168.1.20 5 typ host", "10.42.0.7"))
        assertFalse(IceCandidates.isOnAddress("candidate:1 1 udp 1 2001:db8::1 5 typ host", "fd10:100::7"))
        assertFalse(IceCandidates.isOnAddress("candidate:1 1 udp 1 10.42.0.7 5 typ host", ""))
    }

    @Test
    fun neverResolvesAName() {
        assertFalse(IceCandidates.isOnAddress("candidate:1 1 udp 1 localhost 5 typ host", "127.0.0.1"))
        assertFalse(IceCandidates.isOnAddress("candidate:1 1 udp 1 abc.local 5 typ host", "127.0.0.1"))
    }

    @Test
    fun aTruncatedLineHasNoAddress() {
        assertFalse(IceCandidates.isOnAddress("candidate:1 1 udp", "10.42.0.7"))
    }
}

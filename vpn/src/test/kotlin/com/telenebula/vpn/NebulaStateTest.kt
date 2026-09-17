package com.telenebula.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NebulaStateTest {
    @Test
    fun `going down clears the start time and keeps the error`() {
        NebulaState.startedAt = 42
        NebulaState.emit(true)
        assertEquals(42, NebulaState.state.value.startedAt)
        NebulaState.emit(false, "boom")
        val down = NebulaState.state.value
        assertFalse(down.running)
        assertEquals("boom", down.error)
        assertEquals(0, down.startedAt)
        assertEquals(0, NebulaState.startedAt)
    }

    @Test
    fun `coming up publishes no error`() {
        NebulaState.emit(false, "old")
        NebulaState.emit(true)
        assertNull(NebulaState.state.value.error)
    }
}

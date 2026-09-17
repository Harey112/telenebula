package com.telenebula.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallDiagnosticsTest {
    @Test
    fun `lines are stamped from the start of the attempt and the trail is bounded`() {
        var now = 10_000L
        val diag = CallDiagnostics(isVerbose = { false }, now = { now })
        diag.begin("outgoing", "call-1234567890", "fd::1")
        now += 1_234
        diag.note("peer is ringing")
        assertEquals(listOf("+0.000s  outgoing call call-123 with fd::1", "+1.234s  peer is ringing"), diag.trail.value)

        repeat(200) { diag.note("state $it") }
        assertEquals(CallDiagnostics.MAX_LINES, diag.trail.value.size)
        assertTrue(diag.trail.value.last().endsWith("state 199"))
    }

    @Test
    fun `a new attempt replaces the previous trail`() {
        val diag = CallDiagnostics(isVerbose = { false }, now = { 0 })
        diag.begin("incoming", "a", "fd::1")
        diag.note("x")
        diag.begin("outgoing", "b", "fd::2")
        assertEquals(listOf("+0.000s  outgoing call b with fd::2"), diag.trail.value)
    }
}

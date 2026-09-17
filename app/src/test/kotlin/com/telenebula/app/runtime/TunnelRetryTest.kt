package com.telenebula.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelRetryTest {
    @Test
    fun `the ladder climbs to five minutes and stays there`() {
        assertEquals(listOf(5_000L, 15_000L, 60_000L, 300_000L, 300_000L, 300_000L), (0..5).map(TunnelRetry::delayMs))
        assertEquals(5_000L, TunnelRetry.delayMs(-1))
    }
}

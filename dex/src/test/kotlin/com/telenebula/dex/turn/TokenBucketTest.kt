package com.telenebula.dex.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBucketTest {
    @Test
    fun `a burst is spent and refills at the rate, never past the burst`() {
        var clock = 0L
        val bucket = TokenBucket(perSecond = 10, burst = 3, now = { clock })
        assertEquals(3, (1..10).count { bucket.tryTake() })
        clock += 100
        assertTrue(bucket.tryTake())
        assertFalse(bucket.tryTake())
        clock += 60_000
        assertEquals(3, (1..10).count { bucket.tryTake() })
    }
}

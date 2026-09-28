package com.telenebula.dex.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UploadBudgetTest {
    @Test
    fun `uploads are bounded per login, in total and by staged bytes, and a ticket frees its share once`() {
        val budget = UploadBudget(maxUploads = 3, maxPerSession = 2, maxBytes = 100)
        val a1 = budget.reserve("a", 10)
        val a2 = budget.reserve("a", 10)
        assertNotNull(a1)
        assertNotNull(a2)
        assertNull(budget.reserve("a", 10))
        val b1 = budget.reserve("b", 70)
        assertNotNull(b1)
        assertNull(budget.reserve("c", 1))
        b1?.close()
        b1?.close()
        assertEquals(20L, budget.stagedBytes)
        assertNull(budget.reserve("c", 81))
        val c1 = budget.reserve("c", 80)
        assertNotNull(c1)
        a1?.close()
        a2?.close()
        c1?.close()
        assertEquals(0L, budget.stagedBytes)
        assertNotNull(budget.reserve("a", 100))
    }
}

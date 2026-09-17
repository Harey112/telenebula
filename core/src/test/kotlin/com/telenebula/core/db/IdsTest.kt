package com.telenebula.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsTest {
    private val shape = Regex("[0-9a-f]{32}")

    @Test
    fun `ids keep the shape the database used to mint`() {
        repeat(100) { assertTrue(shape.matches(Ids.newId())) }
    }

    @Test
    fun `ids do not repeat`() {
        val ids = HashSet<String>()
        repeat(10_000) { ids += Ids.newId() }
        assertEquals(10_000, ids.size)
    }
}

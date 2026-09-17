package com.telenebula.core

import com.telenebula.core.events.CoreEvent
import com.telenebula.core.events.CoreEventBus
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingStoreTest {
    @Test
    fun `a typing peer expires unless refreshed, and a stop clears it at once`() = runTest {
        val store = TypingStore(CoreEventBus, backgroundScope, ttlMs = 1_000)
        runCurrent()
        CoreEventBus.emit(CoreEvent.Typing("fd::t1", true))
        runCurrent()
        assertEquals(setOf("fd::t1"), store.typing.value)

        advanceTimeBy(700)
        CoreEventBus.emit(CoreEvent.Typing("fd::t1", true))
        advanceTimeBy(700)
        assertEquals("the refresh restarted the clock", setOf("fd::t1"), store.typing.value)

        advanceTimeBy(400)
        assertTrue(store.typing.value.isEmpty())

        CoreEventBus.emit(CoreEvent.Typing("fd::t1", true))
        runCurrent()
        CoreEventBus.emit(CoreEvent.Typing("fd::t1", false))
        runCurrent()
        assertTrue(store.typing.value.isEmpty())
    }
}

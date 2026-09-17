package com.telenebula.core.engine

import com.telenebula.core.events.CoreEvent
import com.telenebula.core.model.PeerQueueState
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPumpTest {
    @Test
    fun `a burst of changes is one invalidation per kind per flush`() = runTest {
        val sink = ArrayList<CoreEvent>()
        val pump = EventPump(backgroundScope, sink::add)
        repeat(50) { pump.chatChanged("fd::1") }
        repeat(20) { pump.transferProgress("t1", it / 20.0) }
        pump.peerQueue(PeerQueueState(ip = "fd::1", queued = 5))
        pump.peerQueue(PeerQueueState(ip = "fd::1", queued = 2))
        runCurrent()
        assertTrue("nothing leaves before the flush", sink.isEmpty())

        advanceTimeBy(Limits.EVENT_FLUSH_MS + 1)
        assertEquals(listOf(CoreEvent.ChatChanged("fd::1")), sink.filterIsInstance<CoreEvent.ChatChanged>())
        assertEquals(1, sink.count { it is CoreEvent.SummariesChanged })
        assertEquals(listOf(19 / 20.0), sink.filterIsInstance<CoreEvent.TransferProgress>().map { it.pct })
        assertEquals(listOf(2), sink.filterIsInstance<CoreEvent.PeerQueue>().map { it.state.queued })
    }

    @Test
    fun `named rows merge into one announcement, and a structural change swallows them`() = runTest {
        val sink = ArrayList<CoreEvent>()
        val pump = EventPump(backgroundScope, sink::add)
        pump.messagesChanged("fd::1", listOf("m1", "m2"))
        pump.messagesChanged("fd::1", listOf("m2", "m3"))
        pump.contactChanged("fd::2")
        advanceTimeBy(Limits.EVENT_FLUSH_MS + 1)
        assertEquals(
            listOf(CoreEvent.ChatChanged("fd::1", setOf("m1", "m2", "m3")), CoreEvent.ChatChanged("fd::2", emptySet(), hasContactChange = true)),
            sink.filterIsInstance<CoreEvent.ChatChanged>(),
        )

        sink.clear()
        pump.messagesChanged("fd::1", listOf("m4"))
        pump.chatChanged("fd::1")
        pump.messagesChanged("fd::1", listOf("m5"))
        advanceTimeBy(Limits.EVENT_FLUSH_MS + 1)
        assertEquals(listOf(CoreEvent.ChatChanged("fd::1")), sink.filterIsInstance<CoreEvent.ChatChanged>())

        sink.clear()
        pump.messagesChanged("fd::1", (1..Limits.MAX_DELTA_IDS + 1).map { "r$it" })
        advanceTimeBy(Limits.EVENT_FLUSH_MS + 1)
        assertEquals("too many ids is a plain re-read", listOf(CoreEvent.ChatChanged("fd::1")), sink.filterIsInstance<CoreEvent.ChatChanged>())
    }

    @Test
    fun `signals and faults pass straight through, in order, without waiting for a flush`() = runTest {
        val sink = ArrayList<CoreEvent>()
        val pump = EventPump(backgroundScope, sink::add)
        pump.emit(CoreEvent.Typing("fd::1", true))
        pump.fault("Listener", "port busy")
        pump.emit(CoreEvent.Typing("fd::1", false))
        runCurrent()
        assertEquals(
            listOf(CoreEvent.Typing("fd::1", true), CoreEvent.EngineFault("Listener", "port busy"), CoreEvent.Typing("fd::1", false)),
            sink,
        )
    }

    @Test
    fun `a call log announces itself apart from the chat`() = runTest {
        val sink = ArrayList<CoreEvent>()
        val pump = EventPump(backgroundScope, sink::add)
        pump.callLogsChanged()
        advanceTimeBy(Limits.EVENT_FLUSH_MS + 1)
        assertEquals(listOf<CoreEvent>(CoreEvent.CallLogsChanged), sink)
    }
}

package com.telenebula.app.notices

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeCenterTest {
    @Test
    fun `errors and warnings are deduplicated by text`() {
        val notices = NoticeCenter()
        notices.addError("boom")
        notices.addError("boom")
        notices.addWarning("hm")
        notices.addWarning("hm")
        assertEquals(listOf("boom"), notices.state.value.errors)
        assertEquals(listOf("hm"), notices.state.value.warnings)
        notices.popWarning()
        assertTrue(notices.state.value.warnings.isEmpty())
    }

    @Test
    fun `dismissing a success runs its callback exactly once`() {
        val notices = NoticeCenter()
        var runs = 0
        notices.setSuccess("done") { runs += 1 }
        notices.clearSuccess()
        notices.clearSuccess()
        assertEquals(1, runs)
        assertNull(notices.state.value.success)
    }

    @Test
    fun `withLoading clears the loading slot even when the block throws`() = runTest {
        val notices = NoticeCenter()
        try {
            notices.withLoading("working") { throw IllegalStateException("nope") }
        } catch (_: IllegalStateException) {
        }
        assertNull(notices.state.value.loading)
    }

    @Test
    fun `reporting turns a failure into one error and lets cancellation through`() = runTest {
        val notices = NoticeCenter()
        notices.reporting("Couldn't do it") { throw IllegalStateException("disk full") }
        assertEquals(listOf("Couldn't do it: disk full"), notices.state.value.errors)
        var cancelled = false
        try {
            notices.reporting("never shown") { throw kotlinx.coroutines.CancellationException("bye") }
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(1, notices.state.value.errors.size)
    }
}

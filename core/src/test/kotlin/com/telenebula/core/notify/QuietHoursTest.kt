package com.telenebula.core.notify

import com.telenebula.core.model.QuietHours
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {
    private fun at(hour: Int, minute: Int) = hour * 60 + minute

    @Test
    fun `a window that wraps midnight is quiet on both sides of it`() {
        val q = QuietHours(enabled = true, fromHour = 22, fromMinute = 30, toHour = 7, toMinute = 15)
        assertTrue(NotificationPrefsStore.isQuietAt(q, at(22, 30)))
        assertTrue(NotificationPrefsStore.isQuietAt(q, at(3, 0)))
        assertTrue(NotificationPrefsStore.isQuietAt(q, at(7, 14)))
        assertFalse(NotificationPrefsStore.isQuietAt(q, at(22, 29)))
        assertFalse(NotificationPrefsStore.isQuietAt(q, at(7, 15)))
        assertFalse(NotificationPrefsStore.isQuietAt(q, at(12, 0)))
    }

    @Test
    fun `a window inside one day ends on its own minute`() {
        val q = QuietHours(enabled = true, fromHour = 9, fromMinute = 5, toHour = 17, toMinute = 45)
        assertTrue(NotificationPrefsStore.isQuietAt(q, at(9, 5)))
        assertTrue(NotificationPrefsStore.isQuietAt(q, at(17, 44)))
        assertFalse(NotificationPrefsStore.isQuietAt(q, at(9, 4)))
        assertFalse(NotificationPrefsStore.isQuietAt(q, at(17, 45)))
    }

    @Test
    fun `a window with the same start and end is never quiet`() {
        val q = QuietHours(enabled = true, fromHour = 22, toHour = 22)
        assertFalse(NotificationPrefsStore.isQuietAt(q, at(22, 0)))
        assertFalse(NotificationPrefsStore.isQuietAt(q, at(3, 0)))
    }
}

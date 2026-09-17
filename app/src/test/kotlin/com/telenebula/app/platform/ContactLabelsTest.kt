package com.telenebula.app.platform

import com.telenebula.core.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactLabelsTest {
    @Test
    fun `an overlay address is hex groups and colons`() {
        assertTrue(ContactLabels.isOverlayIp("fd10:100::107"))
        assertTrue(ContactLabels.isOverlayIp(" FD10:100::107 "))
        assertFalse(ContactLabels.isOverlayIp("192.168.1.1"))
        assertFalse(ContactLabels.isOverlayIp("fd10:100::107/64"))
        assertFalse(ContactLabels.isOverlayIp("../../etc"))
        assertFalse(ContactLabels.isOverlayIp(""))
    }

    @Test
    fun `chat and contact labels prefer different names`() {
        val contact = Contact(ip = "fd::1", name = "alice", nickname = "Al", addedAt = 0)
        assertEquals("Al", ContactLabels.chatLabel(contact))
        assertEquals("alice", ContactLabels.contactLabel(contact))
        val unnamed = Contact(ip = "fd::2", name = "", addedAt = 0)
        assertEquals("fd::2", ContactLabels.chatLabel(unnamed))
        assertEquals("fd::2", ContactLabels.contactLabel(unnamed))
    }

    @Test
    fun `muting is forever or until a moment`() {
        assertTrue(ContactLabels.isMuted(MUTE_FOREVER, now = 5))
        assertTrue(ContactLabels.isMuted(10, now = 5))
        assertFalse(ContactLabels.isMuted(10, now = 15))
        assertFalse(ContactLabels.isMuted(0, now = 15))
    }
}

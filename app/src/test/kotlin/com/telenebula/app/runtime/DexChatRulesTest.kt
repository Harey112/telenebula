package com.telenebula.app.runtime

import com.telenebula.core.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DexChatRulesTest {
    private val ann = Contact(ip = "fd00::2", name = "ann", addedAt = 0)

    @Test
    fun `an archived or blocked chat refuses what the phone's composer would not offer`() {
        assertNull(DexChatRules.sendRefusal(ann))
        assertNotNull(DexChatRules.sendRefusal(ann.copy(isArchived = true)))
        assertNotNull(DexChatRules.sendRefusal(ann.copy(isBlocked = true)))
        assertNotNull(DexChatRules.editRefusal(ann.copy(isArchived = true)))
        assertNull(DexChatRules.editRefusal(ann.copy(isBlocked = true)))
    }

    @Test
    fun `a call needs an open chat, an unblocked contact and the tunnel`() {
        assertNull(DexChatRules.callRefusal(ann, isTunnelOn = true))
        assertNotNull(DexChatRules.callRefusal(ann, isTunnelOn = false))
        assertNotNull(DexChatRules.callRefusal(ann.copy(isArchived = true), isTunnelOn = true))
        assertNotNull(DexChatRules.callRefusal(ann.copy(isBlocked = true), isTunnelOn = true))
    }

    @Test
    fun `a new contact is saved under the address as the core writes it`() {
        assertEquals("fd00:1234:5678::3", DexChatRules.newContactAddress(" [FD00:1234:5678::3] ", "fd00::1", isSaved = false).getOrThrow())
    }

    @Test
    fun `a new contact is refused for an empty, foreign, own or saved address`() {
        for ((raw, isSaved) in listOf("" to false, "10.0.0.3" to false, "fd00::1" to false, "fd00::3" to true,
            ":::" to false, "fd00:::2" to false, "::" to false, "fd00::1::2" to false, "12345::1" to false)) {
            val result = DexChatRules.newContactAddress(raw, "fd00::1", isSaved)
            assertNotNull(raw, result.exceptionOrNull()?.message)
        }
    }
}

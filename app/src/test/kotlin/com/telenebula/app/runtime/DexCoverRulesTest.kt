package com.telenebula.app.runtime

import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.CoverRevealGate
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus
import com.telenebula.dex.wire.DexAttachment
import com.telenebula.dex.wire.DexDirection
import com.telenebula.dex.wire.DexMessage
import com.telenebula.dex.wire.DexMessageKind
import com.telenebula.dex.wire.DexMessageStatus
import com.telenebula.dex.wire.DexRevealGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexCoverRulesTest {
    private val photo = DexMessage(
        id = "m1",
        peer = "fd00::2",
        dir = DexDirection.IN,
        body = "the caption",
        ts = 1,
        status = DexMessageStatus.RECEIVED,
        kind = DexMessageKind.IMAGE,
        att = DexAttachment(name = "secret-plan.jpg", mime = "image/jpeg", size = 10, hasFile = true),
        reactions = mapOf("fd00::1" to "👍"),
        isCovered = true,
    )

    private fun row(id: String, isCovered: Boolean) =
        ChatMessage(id = id, peerIp = "fd00::2", direction = MessageDirection.IN, body = "text $id", ts = 1, status = MessageStatus.RECEIVED, kind = MessageKind.TEXT, isCovered = isCovered)

    @Test
    fun `behind a code or the phone's lock a covered message keeps only its place and its cover`() {
        for (gate in listOf(DexRevealGate.CODE, DexRevealGate.DEVICE)) {
            val shown = DexCoverRules.shown(photo, gate)
            assertEquals("", shown.body)
            assertEquals(null, shown.att)
            assertEquals(DexMessageKind.TEXT, shown.kind)
            assertTrue(shown.isCovered)
            assertEquals(photo.id, shown.id)
            assertEquals(photo.reactions, shown.reactions)
        }
    }

    @Test
    fun `tap and ask send the content under the cover the page draws, and an uncovered message is never touched`() {
        for (gate in listOf(DexRevealGate.TAP, DexRevealGate.ASK)) assertEquals(photo, DexCoverRules.shown(photo, gate))
        val plain = photo.copy(isCovered = false)
        for (gate in DexRevealGate.entries) assertEquals(plain, DexCoverRules.shown(plain, gate))
    }

    @Test
    fun `a file is served only where its message may be opened`() {
        assertTrue(DexCoverRules.isWithheld(isCovered = true, gate = DexRevealGate.CODE))
        assertTrue(DexCoverRules.isWithheld(isCovered = true, gate = DexRevealGate.DEVICE))
        assertFalse(DexCoverRules.isWithheld(isCovered = true, gate = DexRevealGate.TAP))
        assertFalse(DexCoverRules.isWithheld(isCovered = true, gate = DexRevealGate.ASK))
        assertFalse(DexCoverRules.isWithheld(isCovered = false, gate = DexRevealGate.CODE))
    }

    @Test
    fun `search results and media lists never carry a covered message`() {
        val listed = DexCoverRules.listed(listOf(row("a", false), row("b", true), row("c", false)))
        assertEquals(listOf("a", "c"), listed.map { it.id })
    }

    @Test
    fun `the gate that decides is the chat's own, else Dex's, and a phone-locked chat stays withheld`() {
        assertTrue(DexCoverRules.isWithheld(true, DexProfiles.gate(CoverRevealGate.DEVICE, CoverRevealGate.TAP)))
        assertTrue(DexCoverRules.isWithheld(true, DexProfiles.gate(null, CoverRevealGate.CODE)))
        assertFalse(DexCoverRules.isWithheld(true, DexProfiles.gate(CoverRevealGate.TAP, CoverRevealGate.CODE)))
    }
}

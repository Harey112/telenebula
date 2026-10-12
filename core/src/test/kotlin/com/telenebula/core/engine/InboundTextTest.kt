package com.telenebula.core.engine

import com.telenebula.core.model.Envelope
import com.telenebula.core.model.EnvelopeFrom
import com.telenebula.core.model.EnvelopeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboundTextTest {
    private val now = 1_000_000_000L
    private fun envelope(body: String? = null, name: String = "", emoji: String? = null, ts: Long = now) =
        Envelope(type = EnvelopeType.MSG, from = EnvelopeFrom(ip = "fd::1", name = name), body = body, emoji = emoji, ts = ts)

    @Test
    fun `what fits is left alone`() {
        val e = envelope(body = "hi", name = "alice", emoji = "👍")
        assertEquals(e, InboundText.bound(e, now))
    }

    @Test
    fun `long text and names are cut to their limits`() {
        val bounded = InboundText.bound(envelope(body = "x".repeat(Limits.MAX_BODY_CHARS + 10), name = "n".repeat(500)), now)
        assertEquals(Limits.MAX_BODY_CHARS, bounded.body?.length)
        assertEquals(Limits.MAX_NAME_CHARS, bounded.from.name.length)
    }

    @Test
    fun `a cut never splits a surrogate pair`() {
        val name = "a".repeat(Limits.MAX_NAME_CHARS - 1) + "😀"
        val cut = InboundText.bound(envelope(name = name), now).from.name
        assertEquals(Limits.MAX_NAME_CHARS - 1, cut.length)
    }

    @Test
    fun `a paragraph is not a reaction`() {
        assertNull(InboundText.bound(envelope(emoji = "x".repeat(100)), now).emoji)
    }

    @Test
    fun `a timestamp from the future becomes now, one from the past stays`() {
        assertEquals(now, InboundText.bound(envelope(ts = now + 3_600_000), now).ts)
        assertEquals(now - 86_400_000, InboundText.bound(envelope(ts = now - 86_400_000), now).ts)
        assertEquals(now + 1_000, InboundText.bound(envelope(ts = now + 1_000), now).ts)
    }
}

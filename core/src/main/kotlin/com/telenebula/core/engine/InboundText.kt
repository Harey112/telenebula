package com.telenebula.core.engine

import com.telenebula.core.model.Envelope

/** Bounds what a peer writes into this device: lengths, and a timestamp that may not come from the future. */
internal object InboundText {
    fun bound(envelope: Envelope, nowMs: Long): Envelope = envelope.copy(
        from = envelope.from.copy(name = envelope.from.name.cut(Limits.MAX_NAME_CHARS)),
        body = envelope.body?.cut(Limits.MAX_BODY_CHARS),
        newBody = envelope.newBody?.cut(Limits.MAX_BODY_CHARS),
        name = envelope.name?.cut(Limits.MAX_NAME_CHARS),
        emoji = envelope.emoji?.takeIf { it.length <= Limits.MAX_EMOJI_CHARS },
        ts = if (envelope.ts > nowMs + Limits.MAX_CLOCK_SKEW_MS) nowMs else envelope.ts,
    )

    // never between the two halves of a surrogate pair
    private fun String.cut(max: Int): String {
        if (length <= max) return this
        val end = if (Character.isHighSurrogate(this[max - 1])) max - 1 else max
        return substring(0, end)
    }
}

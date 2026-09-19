package com.telenebula.core.engine

import com.telenebula.core.model.Envelope

/** The one rule for a cover on the wire: trimmed, capped, and absent rather than empty. */
internal object CoverText {
    fun of(cover: String?): String? = cover?.trim()?.take(Limits.MAX_COVER_CHARS)?.takeIf { it.isNotEmpty() }

    fun of(envelope: Envelope): String? = of(envelope.cover)
}

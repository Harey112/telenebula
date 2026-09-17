package com.telenebula.core

import com.telenebula.core.events.CoreEvent
import com.telenebula.core.events.CoreEventBus
import com.telenebula.core.model.PeerPresence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * What each peer's last answer said about it. Presence is only ever learnt from a pong or from a
 * probe that went unanswered, so an entry is a fact about one moment: [fresh] is how a reader asks
 * whether that moment is still recent enough to show.
 */
class PresenceStore(bus: CoreEventBus, scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis) {
    class Entry(val presence: PeerPresence, val atMs: Long)

    val presence: StateFlow<Map<String, Entry>> = busReducer<CoreEvent.PresenceChanged, Map<String, Entry>>(bus, scope, emptyMap()) { current, e ->
        val next = current + (e.ip to Entry(e.presence, now()))
        if (next.size <= MAX_PEERS) next else next.entries.minByOrNull { it.value.atMs }?.let { oldest -> next - oldest.key } ?: next
    }

    fun of(ip: String): Entry? = presence.value[ip]

    companion object {
        /** answers remembered at once; the oldest makes room for a new peer */
        const val MAX_PEERS = 256

        /** three refresh intervals of the open chat; older than this says nothing about now */
        const val FRESH_MS = 90_000L

        fun fresh(entry: Entry?, nowMs: Long): PeerPresence? = entry?.takeIf { nowMs - it.atMs < FRESH_MS }?.presence
    }
}

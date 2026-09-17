package com.telenebula.core

import com.telenebula.core.events.CoreEvent
import com.telenebula.core.events.CoreEventBus
import com.telenebula.core.model.PeerQueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * What each peer's outbound queue is doing, as the engine last reported it. A peer with nothing
 * waiting drops out of the map, so this is bounded by peers that have work rather than by the
 * contact list — the same rule the scheduler itself follows.
 *
 * The engine pushes these; nothing here polls and nothing here re-queries. It is the answer to
 * "why has this not sent yet", which no database read can give.
 */
class PeerQueueStore(bus: CoreEventBus, scope: CoroutineScope) {
    val queues: StateFlow<Map<String, PeerQueueState>> = busReducer<CoreEvent.PeerQueue, Map<String, PeerQueueState>>(bus, scope, emptyMap()) { current, event ->
        val peer = event.state
        if (peer.queued == 0 && !peer.isDraining) current - peer.ip else current + (peer.ip to peer)
    }

    fun of(ip: String): PeerQueueState? = queues.value[ip]
}

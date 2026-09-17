package com.telenebula.core

import com.telenebula.core.events.CoreEvent
import com.telenebula.core.events.CoreEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** action id → transfer progress in [0, 1]; a negative pct from the core removes the entry. */
class TransferProgressStore(bus: CoreEventBus, scope: CoroutineScope) {
    val progress: StateFlow<Map<String, Double>> = busReducer<CoreEvent.TransferProgress, Map<String, Double>>(bus, scope, emptyMap()) { current, e ->
        when {
            e.pct >= 0 -> current + (e.actionId to e.pct)
            e.actionId in current -> current - e.actionId
            else -> current
        }
    }
}

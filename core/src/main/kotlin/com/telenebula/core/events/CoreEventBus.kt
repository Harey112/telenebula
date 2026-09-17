package com.telenebula.core.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide event stream. The engine's pump and the notification receivers write with
 * `tryEmit`, so nothing here ever blocks or throws back into the engine. Every event is
 * re-derivable, which is why the oldest is dropped when nobody keeps up.
 */
object CoreEventBus {
    private val flow = MutableSharedFlow<CoreEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<CoreEvent> = flow.asSharedFlow()

    fun emit(event: CoreEvent) {
        flow.tryEmit(event)
    }
}

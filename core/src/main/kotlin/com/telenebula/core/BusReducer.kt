package com.telenebula.core

import com.telenebula.core.events.CoreEvent
import com.telenebula.core.events.CoreEventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Folds one kind of bus event into a state. A reducer that threw would freeze its store for the rest of the process, so it is announced instead. */
internal inline fun <reified E : CoreEvent, S> busReducer(
    bus: CoreEventBus,
    scope: CoroutineScope,
    initial: S,
    crossinline reduce: (S, E) -> S,
): StateFlow<S> {
    val state = MutableStateFlow(initial)
    scope.launch {
        try {
            bus.events.filterIsInstance<E>().collect { event -> state.update { reduce(it, event) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            bus.emit(CoreEvent.EngineFault("Live state stopped updating", e.message ?: e.javaClass.simpleName))
        }
    }
    return state.asStateFlow()
}

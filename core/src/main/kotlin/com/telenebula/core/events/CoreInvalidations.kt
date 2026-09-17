package com.telenebula.core.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription

/**
 * "Something you queried may have changed" signals; the client debounces and re-queries.
 *
 * Each flow ticks once as soon as its bus subscription is live (`onSubscription`, so no event
 * between that first read and the subscription is ever lost), then on every matching event.
 */
class CoreInvalidations(private val bus: CoreEventBus) {
    private val manual = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun summaries(): Flow<Unit> = merge(
        bus.events.onSubscription { emit(CoreEvent.SummariesChanged) }.mapNotNull { if (it is CoreEvent.SummariesChanged) Unit else null },
        manual,
    )

    fun callLogs(): Flow<Unit> = merge(
        bus.events.onSubscription { emit(CoreEvent.CallLogsChanged) }.mapNotNull { if (it is CoreEvent.CallLogsChanged) Unit else null },
        manual,
    )

    fun chat(ip: String): Flow<Unit> = merge(
        bus.events.onSubscription { emit(CoreEvent.ChatChanged(ip)) }.mapNotNull { if (it is CoreEvent.ChatChanged && it.ip == ip) Unit else null },
        manual,
    )

    /** The chat's announcements with their detail; the first is a full read, and so is a manual refresh. */
    fun chatDeltas(ip: String): Flow<CoreEvent.ChatChanged> = merge(
        bus.events.onSubscription { emit(CoreEvent.ChatChanged(ip)) }.mapNotNull { (it as? CoreEvent.ChatChanged)?.takeIf { e -> e.ip == ip } },
        manual.map { CoreEvent.ChatChanged(ip) },
    )

    /** Re-runs every live query (after a restore, a clear, a contact edit done off the event path). */
    fun refresh() {
        manual.tryEmit(Unit)
    }

    companion object {
        const val BUMP_COALESCE_MS = 50L
    }
}

package com.telenebula.core.engine

import com.telenebula.core.events.CoreEvent
import com.telenebula.core.model.PeerQueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The engine's events, coalesced before they reach the app. A chat that changes fifty times while
 * a transfer runs is one invalidation per flush, and progress is only reported in [Limits.PROGRESS_STEP]
 * steps — a screen re-queries at most once per [Limits.EVENT_FLUSH_MS] no matter what the network does.
 *
 * Signals, typing and message notifications pass straight through: latency is what matters there.
 */
internal class EventPump(scope: CoroutineScope, private val sink: (CoreEvent) -> Unit) {
    /** Its own channel: nothing re-derives a call offer or a typing signal, so none may be dropped by a burst. */
    private val immediate = Channel<CoreEvent>(capacity = IMMEDIATE_CAP, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Written under its own lock; bounded by peers and transfers, never by how often they change. */
    private val pending = Pending()

    /** what one chat's next announcement says; a structural change swallows the ids, a long id list becomes structural */
    private class ChatDelta {
        var isStructural = false
        var hasContactChange = false
        val ids = LinkedHashSet<String>()
    }

    private class Pending {
        val chats = LinkedHashMap<String, ChatDelta>()
        var summaries = false
        var callLogs = false
        val progress = LinkedHashMap<String, Double>()

        /** last writer wins per peer: a drain changes one peer's state many times in a burst */
        val queues = LinkedHashMap<String, PeerQueueState>()
    }

    init {
        scope.launch {
            for (event in immediate) sink(event)
        }
        scope.launch {
            while (isActive) {
                delay(Limits.EVENT_FLUSH_MS)
                flush()
            }
        }
    }

    /** Rows were added or removed: the chat is read again in full. */
    fun chatChanged(ip: String) = synchronized(pending) {
        pending.chats.getOrPut(ip) { ChatDelta() }.isStructural = true
        pending.summaries = true
    }

    /** Only these rows changed (status, reactions, edits, receipts); the reader patches them in. */
    fun messagesChanged(ip: String, ids: Collection<String>) = synchronized(pending) {
        val delta = pending.chats.getOrPut(ip) { ChatDelta() }
        delta.ids += ids
        if (delta.ids.size > Limits.MAX_DELTA_IDS) delta.isStructural = true
        pending.summaries = true
    }

    /** The contact row changed (presence, flags, nickname); no message did. */
    fun contactChanged(ip: String) = synchronized(pending) {
        pending.chats.getOrPut(ip) { ChatDelta() }.hasContactChange = true
        pending.summaries = true
    }

    fun summariesChanged() = synchronized(pending) {
        pending.summaries = true
    }

    fun callLogsChanged() = synchronized(pending) {
        pending.callLogs = true
    }

    /** pct in [0,1]; a negative value clears the entry on the app side. */
    fun transferProgress(actionId: String, pct: Double) = synchronized(pending) {
        pending.progress[actionId] = pct
    }

    /** One peer's queue state. Coalesced like the rest: a drain must not spam the app per action. */
    fun peerQueue(state: PeerQueueState) = synchronized(pending) {
        pending.queues[state.ip] = state
    }

    fun emit(event: CoreEvent) {
        immediate.trySend(event)
    }

    /** A failure with nobody to throw to. */
    fun fault(what: String, message: String) = emit(CoreEvent.EngineFault(what, message))

    private fun flush() {
        val chats: List<Pair<String, ChatDelta>>
        val summaries: Boolean
        val callLogs: Boolean
        val progress: List<Pair<String, Double>>
        val queues: List<PeerQueueState>
        synchronized(pending) {
            chats = pending.chats.map { it.key to it.value }
            summaries = pending.summaries
            callLogs = pending.callLogs
            progress = pending.progress.map { it.key to it.value }
            queues = pending.queues.values.toList()
            pending.chats.clear()
            pending.summaries = false
            pending.callLogs = false
            pending.progress.clear()
            pending.queues.clear()
        }
        for ((ip, delta) in chats) sink(CoreEvent.ChatChanged(ip, if (delta.isStructural) null else delta.ids.toSet(), delta.hasContactChange))
        if (summaries) sink(CoreEvent.SummariesChanged)
        if (callLogs) sink(CoreEvent.CallLogsChanged)
        for ((actionId, pct) in progress) sink(CoreEvent.TransferProgress(actionId, pct))
        for (state in queues) sink(CoreEvent.PeerQueue(state))
    }

    private companion object {
        /** Signals, typing and notifications awaiting the sink; nothing re-derives these, so they are kept apart. */
        const val IMMEDIATE_CAP = 256
    }
}

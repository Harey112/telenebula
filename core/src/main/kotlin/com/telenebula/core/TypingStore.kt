package com.telenebula.core

import com.telenebula.core.events.CoreEvent
import com.telenebula.core.events.CoreEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Peers currently typing. The core does not expire typing signals, so each ip
 * carries one expiry job that a refresh replaces; readers see a plain set.
 */
class TypingStore(bus: CoreEventBus, private val scope: CoroutineScope, private val ttlMs: Long = TYPING_TTL_MS) {
    private val state = MutableStateFlow<Set<String>>(emptySet())
    val typing: StateFlow<Set<String>> = state.asStateFlow()

    private val expiry = HashMap<String, Job>()

    init {
        scope.launch {
            bus.events.filterIsInstance<CoreEvent.Typing>().collect { e -> set(e.fromIp, e.isTyping) }
        }
    }

    private fun set(ip: String, isTyping: Boolean) {
        synchronized(expiry) {
            expiry.remove(ip)?.cancel()
            if (isTyping) expiry[ip] = scope.launch { expire(ip) }
        }
        state.update { current ->
            when {
                isTyping -> if (ip in current) current else current + ip
                ip in current -> current - ip
                else -> current
            }
        }
    }

    private suspend fun expire(ip: String) {
        delay(ttlMs)
        // a refresh that landed after the delay owns the entry now; only the current job may expire it
        val me = coroutineContext[Job]
        val owns = synchronized(expiry) { expiry[ip] === me && expiry.remove(ip) != null }
        if (owns) state.update { current -> if (ip in current) current - ip else current }
    }

    companion object {
        const val TYPING_TTL_MS = 6000L
    }
}

package com.telenebula.calls

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The last call's connection trail: candidates, transport states, watchdogs and the reason it
 * ended, timestamped from the moment the call started. Always kept (bounded) for the
 * Diagnostics screen; mirrored to logcat only while the verbose switch is on. Failures still
 * reach the user through the call state, never through this log alone.
 */
class CallDiagnostics(private val isVerbose: () -> Boolean, private val now: () -> Long = System::currentTimeMillis) {
    private val lines = MutableStateFlow<List<String>>(emptyList())
    val trail: StateFlow<List<String>> = lines.asStateFlow()
    private var startedAt = 0L

    /** A new attempt replaces the previous trail. */
    fun begin(role: String, callId: String, peerIp: String) {
        startedAt = now()
        lines.value = emptyList()
        note("$role call ${callId.take(8)} with $peerIp")
    }

    fun note(text: String) {
        val elapsed = (now() - startedAt).coerceAtLeast(0)
        val line = "+%d.%03ds  %s".format(elapsed / 1000, elapsed % 1000, text)
        lines.update { (it + line).takeLast(MAX_LINES) }
        if (isVerbose()) Log.d(TAG, text)
    }

    companion object {
        const val TAG = "TnCalls"
        const val MAX_LINES = 80
    }
}

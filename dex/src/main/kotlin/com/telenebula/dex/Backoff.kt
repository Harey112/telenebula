package com.telenebula.dex

import kotlinx.coroutines.delay

/** A socket loop whose call keeps failing waits longer each time instead of spinning, and reports the streak once. */
class Backoff(private val what: String, private val onFault: (String) -> Unit) {
    private var failures = 0

    fun reset() {
        failures = 0
    }

    suspend fun failed(error: Throwable) {
        failures += 1
        if (failures == Limits.LOOP_FAILURES_BEFORE_REPORT) onFault("$what keeps failing: ${error.message ?: error.javaClass.simpleName}")
        delay((Limits.LOOP_BACKOFF_MIN_MS shl (failures - 1).coerceAtMost(BACKOFF_DOUBLINGS)).coerceAtMost(Limits.LOOP_BACKOFF_MAX_MS))
    }

    private companion object {
        const val BACKOFF_DOUBLINGS = 10
    }
}

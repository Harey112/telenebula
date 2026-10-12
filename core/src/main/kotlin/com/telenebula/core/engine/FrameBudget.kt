package com.telenebula.core.engine

/** A token bucket over one connection's frames; not thread-safe, its reader is its only caller. */
internal class FrameBudget(
    private val perSecond: Int = Limits.FRAME_RATE_PER_SEC,
    private val burst: Int = Limits.FRAME_BURST,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var tokens = burst.toDouble()
    private var last = nowNanos()

    fun take(): Boolean {
        val now = nowNanos()
        tokens = minOf(burst.toDouble(), tokens + (now - last) / 1e9 * perSecond)
        last = now
        if (tokens < 1) return false
        tokens -= 1
        return true
    }
}

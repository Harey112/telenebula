package com.telenebula.core.engine

import com.telenebula.core.model.PeerQueueState

/**
 * When one peer is next probed, and how far up the backoff ladder its silence has pushed it.
 *
 * Deliberately pure and free of the engine: the ladder is the rule that decides how much work a
 * queue costs while its peer is away, so it is worth being able to test on its own.
 *
 * Every instant here is monotonic ([android.os.SystemClock.elapsedRealtime], or `nanoTime` off a
 * device). A wall clock would park every peer for hours the first time NTP corrected it.
 */
internal class PeerSchedule(val ip: String) {
    @Volatile var stepIndex = 0
        private set

    @Volatile var dueAtMs = 0L
        private set

    @Volatile var isReachable = false
        private set

    @Volatile var isDraining = false

    /** Forgotten while a worker held it; the worker drops it when it is done, so the same peer is never drained twice. */
    @Volatile var isForgotten = false

    /**
     * Work arrived while this peer was being drained. The drain decides when the peer is next due
     * from a count it takes at the end, so a kick landing after that count would otherwise be
     * overwritten and the new action would wait for something else to wake it.
     */
    @Volatile var wokenWhileDraining = false

    @Volatile var queued = 0

    /** Silence pushes the peer one rung up and stops at the floor. It never gives up. */
    fun onSilent(nowMs: Long, jitter: (Long) -> Long = { it }) {
        isReachable = false
        stepIndex = (stepIndex + 1).coerceAtMost(Limits.PING_BACKOFF_MS.lastIndex)
        dueAtMs = nowMs + jitter(Limits.PING_BACKOFF_MS[stepIndex])
    }

    /**
     * Any proof of life — a probe that answered, a frame that arrived, a ping the user asked for —
     * puts the peer back on the fast end and makes it due at once.
     */
    fun onReachable(nowMs: Long) {
        isReachable = true
        stepIndex = 0
        dueAtMs = nowMs
    }

    /**
     * Nothing runnable is left — the queue is empty, or everything in it is parked on an offer the
     * other side has not answered. Either way there is nothing to probe *for*: the peer is woken by
     * new work or by anything it says, never by a timer. Without this a queue of parked offers
     * would be rescheduled at zero delay forever.
     */
    fun sleepUntilWoken() {
        dueAtMs = Long.MAX_VALUE
    }

    /** Come back in [delayMs]; used when a drain filled its pages and there is still work. */
    fun dueIn(nowMs: Long, delayMs: Long) {
        dueAtMs = nowMs + delayMs.coerceAtLeast(1)
    }

    fun isDue(nowMs: Long): Boolean = dueAtMs <= nowMs

    fun snapshot(nowMs: Long, isTunnelUp: Boolean): PeerQueueState = PeerQueueState(
        ip = ip,
        queued = queued,
        isReachable = isReachable,
        isDraining = isDraining,
        nextProbeInMs = if (dueAtMs == Long.MAX_VALUE) null else (dueAtMs - nowMs).coerceAtLeast(0),
        isTunnelUp = isTunnelUp,
    )
}

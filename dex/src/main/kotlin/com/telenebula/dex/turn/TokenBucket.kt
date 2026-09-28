package com.telenebula.dex.turn

/** [perSecond] tokens a second, at most [burst] saved up; one lock, no allocation per take. */
class TokenBucket(private val perSecond: Int, private val burst: Int, private val now: () -> Long) {
    private val lock = Any()
    private var tokens = burst.toDouble()
    private var lastAt = Long.MIN_VALUE

    fun tryTake(): Boolean = synchronized(lock) {
        val stamp = now()
        if (lastAt != Long.MIN_VALUE && stamp > lastAt) tokens = minOf(burst.toDouble(), tokens + (stamp - lastAt) * perSecond / 1000.0)
        lastAt = stamp
        if (tokens < 1.0) return false
        tokens -= 1.0
        true
    }
}

package com.telenebula.app.runtime

/** How long to wait before bringing a tunnel back that fell over on its own; the last rung repeats. */
object TunnelRetry {
    val LADDER_MS = longArrayOf(5_000L, 15_000L, 60_000L, 300_000L)

    fun delayMs(attempt: Int): Long = LADDER_MS[attempt.coerceIn(0, LADDER_MS.lastIndex)]
}

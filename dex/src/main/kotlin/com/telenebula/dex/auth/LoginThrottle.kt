package com.telenebula.dex.auth

import com.telenebula.dex.Limits

/** Failed logins per remote address; an address past the limit waits out the lock. */
class LoginThrottle(
    private val now: () -> Long,
    private val failsBeforeLock: Int = Limits.LOGIN_FAILS_BEFORE_LOCK,
    private val lockMs: Long = Limits.LOGIN_LOCK_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private class Entry(var fails: Int, var lastFailAt: Long, var lockedUntil: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    /** Milliseconds the address must still wait, 0 when it may try. */
    fun lockedFor(address: String): Long {
        val stamp = now()
        return synchronized(lock) {
            purgeLocked(stamp)
            val entry = entries[address] ?: return 0
            (entry.lockedUntil - stamp).coerceAtLeast(0)
        }
    }

    fun recordFailure(address: String) {
        val stamp = now()
        synchronized(lock) {
            purgeLocked(stamp)
            val entry = entries.getOrPut(address) { Entry(0, stamp, 0) }
            entry.fails += 1
            entry.lastFailAt = stamp
            if (entry.fails >= failsBeforeLock) {
                entry.lockedUntil = stamp + lockMs
                entry.fails = 0
            }
            while (entries.size > capacity) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
    }

    fun clear(address: String) {
        synchronized(lock) { entries.remove(address) }
    }

    private fun purgeLocked(stamp: Long) {
        val stale = entries.filter { (_, e) -> e.lockedUntil <= stamp && stamp - e.lastFailAt > lockMs }.keys
        for (key in stale) entries.remove(key)
    }

    companion object {
        const val DEFAULT_CAPACITY = 256
    }
}

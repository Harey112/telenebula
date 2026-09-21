package com.telenebula.dex.auth

import com.telenebula.dex.Limits
import java.security.SecureRandom
import java.util.Base64

class Session(val token: String, val username: String, val createdAt: Long, val lastSeenAt: Long, val remoteAddress: String)

/** Logged-in browsers by cookie token; sliding expiry, oldest evicted past [capacity]. */
class Sessions(private val now: () -> Long, private val capacity: Int = DEFAULT_CAPACITY, private val idleMs: Long = Limits.SESSION_IDLE_MS) {
    private val lock = Any()
    private val random = SecureRandom()
    private val byToken = LinkedHashMap<String, Session>(16, 0.75f, true)

    val size: Int get() = synchronized(lock) { byToken.size }

    fun create(username: String, remoteAddress: String): Session {
        val stamp = now()
        val token = newToken()
        val session = Session(token, username, stamp, stamp, remoteAddress)
        synchronized(lock) {
            purgeLocked(stamp)
            byToken[token] = session
            while (byToken.size > capacity) {
                val oldest = byToken.keys.firstOrNull() ?: break
                byToken.remove(oldest)
            }
        }
        return session
    }

    /** The live session for [token], touched; null when unknown or expired. */
    fun find(token: String?): Session? {
        if (token.isNullOrEmpty() || token.length > MAX_TOKEN_CHARS) return null
        val stamp = now()
        return synchronized(lock) {
            purgeLocked(stamp)
            val current = byToken[token] ?: return null
            val touched = Session(current.token, current.username, current.createdAt, stamp, current.remoteAddress)
            byToken[token] = touched
            touched
        }
    }

    fun remove(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        return synchronized(lock) { byToken.remove(token) != null }
    }

    fun clear() = synchronized(lock) { byToken.clear() }

    private fun purgeLocked(stamp: Long) {
        val expired = byToken.values.filter { stamp - it.lastSeenAt > idleMs }
        for (session in expired) byToken.remove(session.token)
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
        private const val TOKEN_BYTES = 32
        private const val MAX_TOKEN_CHARS = 64
    }
}

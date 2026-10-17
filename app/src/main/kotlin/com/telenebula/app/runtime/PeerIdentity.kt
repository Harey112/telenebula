package com.telenebula.app.runtime

import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.platform.ContactLabels
import com.telenebula.core.CoreClient
import com.telenebula.core.model.FingerprintCheck
import com.telenebula.vpn.model.HostmapEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Compares the certificate nebula authenticated for a peer with the one pinned for that contact. */
class PeerIdentity(
    private val core: CoreClient,
    private val hostInfo: suspend (String) -> HostmapEntry?,
    private val notices: NoticeCenter,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val checkedAt = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_TRACKED
    }

    suspend fun check(ip: String) {
        val key = CoreClient.normalizeIp(ip)
        if (key.isEmpty() || !claim(key)) return
        val host = hostInfo(key)
        val fingerprint = host?.certFingerprint?.takeIf { it.isNotBlank() }
        if (fingerprint == null) {
            mutex.withLock { checkedAt.remove(key) }
            return
        }
        if (core.checkFingerprint(key, fingerprint, host.certName.orEmpty()) == FingerprintCheck.CHANGED) {
            val name = core.contact(key)?.let(ContactLabels::chatLabel) ?: key
            notices.addWarning("$name's certificate changed: Make sure it's still them before you trust it.")
        }
    }

    private suspend fun claim(key: String): Boolean = mutex.withLock {
        val last = checkedAt[key]
        if (last != null && now() - last < RECHECK_MS) return@withLock false
        checkedAt[key] = now()
        true
    }

    private companion object {
        const val RECHECK_MS = 10 * 60_000L
        const val MAX_TRACKED = 256
    }
}

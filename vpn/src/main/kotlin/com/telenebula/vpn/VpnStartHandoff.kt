package com.telenebula.vpn

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal class VpnStartRequest(
    val configJson: String,
    val key: String,
    val networks: List<String>,
    val routes: List<String>,
    val mtu: Int,
)

/** The start parameters stay in this process; the Intent that wakes the service carries only the nonce. */
internal object VpnStartHandoff {
    private class Pending(val nonce: String, val request: VpnStartRequest)

    private val pending = AtomicReference<Pending?>(null)

    fun offer(request: VpnStartRequest): String {
        val nonce = UUID.randomUUID().toString()
        pending.set(Pending(nonce, request))
        return nonce
    }

    fun take(nonce: String?): VpnStartRequest? {
        if (nonce == null) return null
        while (true) {
            val current = pending.get() ?: return null
            if (current.nonce != nonce) return null
            if (pending.compareAndSet(current, null)) return current.request
        }
    }

    fun clear() {
        pending.set(null)
    }
}

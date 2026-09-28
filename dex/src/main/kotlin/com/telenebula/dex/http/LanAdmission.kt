package com.telenebula.dex.http

import com.telenebula.dex.Limits
import java.net.InetAddress

/** Who may use listeners bound to every interface: a connection that reached one of [localAddresses]; a miss re-reads them at most once per [refreshMs]. */
class LanAdmission(
    private val localAddresses: () -> List<InetAddress>,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshMs: Long = Limits.ADMISSION_REFRESH_MS,
) {
    @Volatile private var snapshot: List<InetAddress> = emptyList()
    @Volatile private var readAt: Long = Long.MIN_VALUE

    /** [local] is the address the connection arrived at, so a routed LAN is admitted and the overlay or cellular never is. */
    fun admits(local: InetAddress): Boolean {
        if (snapshot.any { Cidr.sameAddress(it, local) }) return true
        val stamp = now()
        if (readAt != Long.MIN_VALUE && stamp - readAt in 0 until refreshMs) return false
        readAt = stamp
        snapshot = localAddresses()
        return snapshot.any { Cidr.sameAddress(it, local) }
    }

    /** What the last read held, for the report of a refusal. */
    val known: List<InetAddress> get() = snapshot
}

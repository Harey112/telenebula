package com.telenebula.core.engine

import com.telenebula.core.CoreLog
import com.telenebula.core.CorePaths
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The upkeep that used to ride the outbox's 60-second cycle: expiring disappearing messages and
 * moving the traffic counters into the store. Neither has anything to do with delivery, and the
 * delivery loop is event-driven now — so they get their own timer rather than quietly stopping.
 */
internal class Housekeeper(private val engine: Engine) {
    fun start() {
        engine.scope.launch {
            while (currentCoroutineContext().isActive) {
                runCatching { engine.flushTraffic() }
                sweepExpired()
                // on the timer, or a reassembly whose sender vanished holds its file handle open
                runCatching { engine.attachments.purgeStale() }
                delay(Limits.HOUSEKEEPING_INTERVAL_MS)
            }
        }
    }

    /** Deletes the disappearing messages whose timer ran out, and their files. */
    private fun sweepExpired() {
        val rows = runCatching { engine.store.sweepExpired(System.currentTimeMillis(), engine.inFlight) }.getOrElse {
            CoreLog.warn(TAG, "expiry sweep: ${it.message}")
            engine.events.fault("Expiring old messages failed", it.describe())
            return
        }
        val peers = LinkedHashSet<String>()
        for ((peerIp, path) in rows) {
            // a restored backup can carry any uri, so only a file under the attachments dir is ever unlinked
            if (path != null) runCatching { File(path).takeIf { CorePaths.isInside(engine.attachmentsDir, it) }?.delete() }
            peers += peerIp
        }
        for (peer in peers) engine.events.chatChanged(peer)
    }

    companion object {
        private const val TAG = "TnHousekeeper"
    }
}

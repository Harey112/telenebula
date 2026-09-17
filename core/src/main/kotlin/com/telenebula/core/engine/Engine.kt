package com.telenebula.core.engine

import com.telenebula.core.Ip
import com.telenebula.core.db.Store
import com.telenebula.core.events.CoreEvent
import com.telenebula.core.model.PeerPresence
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import com.telenebula.core.CoreLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Who this device is on the overlay, as every outgoing envelope announces it. */
internal class EngineProfile(
    val overlayIp: String,
    val displayName: String,
    val msgPort: Int,
    val appVersion: String,
    /**
     * The address the listener binds and outgoing connections leave from. On a device this is the
     * wildcard — the tunnel decides which interface a peer is reachable on. The tests set it so
     * two engines can run on one host and still see each other as two different peers.
     */
    val bindHost: String = "::",
)

/**
 * The running engine: one scope, the peer registry, the pending acks, the transfers in flight and
 * the counters. Everything it owns is bounded and everything it starts is a child of [scope], so
 * [stop] really stops it — no task outlives the engine that created it.
 */
internal class Engine(
    val store: Store,
    val profile: EngineProfile,
    val attachmentsDir: File,
    sendReadReceipts: Boolean,
    sink: (CoreEvent) -> Unit,
    isTunnelUp: Boolean = true,
    isOnline: Boolean = false,
) {
    // a task that dies with an exception still has to be heard of; the default handler would only log it
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> onUncaught(e) })
    val events = EventPump(scope, sink)

    /** live outbound queues per peer overlay ip (inbound or outbound connections) */
    val peers = ConcurrentHashMap<String, PeerLink>()

    /** peers with a connect in flight; one connect per peer, everyone else waits for it */
    val connecting = PeerGates()

    /**
     * peers with a probe in flight. A pong is keyed by peer (v1 carries no reference to its ping),
     * so two probes at once would steal each other's answer — the scheduler and a ping the user
     * asked for share one instead.
     */
    val probing = PeerGates()

    val acks = AckRegistry()

    /** transferId (= message id) → the reassembly it is streaming into */
    val incoming = ConcurrentHashMap<String, IncomingTransfer>()

    /** actions being attempted right now, so a cycle can never deliver one twice */
    val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** "send read receipts" privacy switch (chats are still marked read locally) */
    val sendReadReceipts = AtomicBoolean(sendReadReceipts)

    fun sendsReadReceiptsTo(peerIp: String): Boolean = store.getContact(peerIp)?.privacy?.sendReadReceipts ?: sendReadReceipts.get()

    /**
     * Whether the overlay is up. Defaults to true so a caller that never reports it (the tests)
     * behaves as before; while it is false nothing is probed, because the failure would be ours.
     */
    val isTunnelUp = AtomicBoolean(isTunnelUp)

    /** this device is in use and shares that; what our pong tells whoever asks */
    val isOnline = AtomicBoolean(isOnline)

    val startedAtMs = System.currentTimeMillis()

    private val traffic = ConcurrentHashMap<String, Traffic>()
    private val stopHooks = CopyOnWriteArrayList<() -> Unit>()

    val transport = Transport(this)
    val attachments = TransferManager(this)
    val sending = OutgoingTransfers(this)
    val sender = ActionSender(this)
    val delivery = DeliveryScheduler(this)
    val outbox = Outbox(this)
    val inbound = InboundDispatcher(this)
    private val housekeeper = Housekeeper(this)

    fun start() {
        transport.startListener()
        delivery.start()
        housekeeper.start()
        scope.launch { outbox.migrateLegacyInlineAttachments() }
    }

    /**
     * A ping somebody asked for. An answer is proof the peer is there, so it is handed straight to
     * the scheduler: whatever is queued for that peer goes now instead of waiting out the rung its
     * silence had climbed to. Reporting the round trip is the smaller half of what this does.
     */
    suspend fun pingPeer(peerIp: String, timeoutMs: Long?): Long {
        if (!isTunnelUp.get()) return Transport.UNREACHABLE
        val rtt = transport.ping(peerIp, timeoutMs)
        if (rtt >= 0) delivery.onProbeSucceeded(peerIp) else events.emit(CoreEvent.PresenceChanged(Ip.normalize(peerIp), PeerPresence.OFFLINE))
        return rtt
    }

    /** The overlay came up or went away; only the scheduler cares, and only to stop probing. */
    fun onTunnelState(running: Boolean) {
        val was = isTunnelUp.getAndSet(running)
        if (was == running) return
        if (running) delivery.onTunnelUp() else delivery.onTunnelDown()
    }

    /** Runs when the engine stops, before its scope is cancelled (closing the listener socket). */
    fun onStop(hook: () -> Unit) {
        stopHooks += hook
    }

    fun stop() {
        stopHooks.forEach { runCatching { it() } }
        peers.values.forEach { it.close() }
        peers.clear()
        incoming.values.forEach { it.discard() }
        incoming.clear()
        runCatching { flushTraffic() }
        scope.cancel()
    }

    fun newId(): String = store.newId()

    private fun onUncaught(e: Throwable) {
        CoreLog.warn(TAG, "engine task failed: ${e.message}")
        events.fault("A messaging task failed", e.describe())
    }

    fun countSent(ip: String, bytes: Int) {
        traffic.computeIfAbsent(ip) { Traffic() }.sent.addAndGet(bytes.toLong())
    }

    fun countReceived(ip: String, bytes: Int) {
        traffic.computeIfAbsent(ip) { Traffic() }.received.addAndGet(bytes.toLong())
    }

    /** Moves the in-memory counters into the store (a no-op when nothing moved). */
    fun flushTraffic() {
        // taken with getAndSet, never removed: a count added between a read and a remove would be lost
        for ((ip, counters) in traffic) {
            val sent = counters.sent.getAndSet(0)
            val received = counters.received.getAndSet(0)
            if (sent != 0L || received != 0L) store.addTraffic(ip, sent, received)
        }
    }

    private companion object {
        const val TAG = "TnEngine"
    }
}

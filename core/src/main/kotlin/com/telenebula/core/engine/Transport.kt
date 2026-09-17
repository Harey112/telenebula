package com.telenebula.core.engine

import com.telenebula.core.CoreLog
import com.telenebula.core.CoreException
import com.telenebula.core.Ip
import com.telenebula.core.model.Envelope
import com.telenebula.core.model.EnvelopeFrom
import com.telenebula.core.model.EnvelopeType
import com.telenebula.core.model.OutboundSignal
import com.telenebula.core.protocol.FrameCodec
import com.telenebula.core.protocol.FrameParser
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How a pending acknowledgement resolved. Silence and a refusal are not the same answer. */
internal sealed interface AckOutcome {
    data object Ack : AckOutcome

    /** the peer answered, and the answer was no */
    data class Nack(val reason: String) : AckOutcome

    data object Timeout : AckOutcome
}

/** One live connection to a peer: its outbound queue, and the socket the reader is parked on. */
internal class PeerLink(
    val ip: String,
    val generation: Long,
    private val socket: Socket,
) {
    /** bounded, so one unreachable peer cannot cost unbounded memory */
    val outbound = Channel<ByteArray>(capacity = Limits.PEER_QUEUE_CAP)

    @Volatile
    private var isClosed = false

    val isAlive: Boolean get() = !isClosed && !socket.isClosed

    /**
     * Closing the socket is also how the reader is woken: a blocking read returns with an error
     * rather than waiting for a peer whose tunnel went away to say something.
     */
    fun close() {
        isClosed = true
        outbound.close()
        runCatching { socket.close() }
    }
}

/** Pending acks, keyed by the envelope id they answer (or `pong:<ip>` for a probe). */
internal class AckRegistry {
    private val waiters = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<AckOutcome>>()

    /** Closing the handle forgets the entry, whichever way the caller leaves: ack, timeout, error or cancellation. */
    fun register(id: String): AckWaiter = AckWaiter(this, id, CompletableDeferred<AckOutcome>().also { waiters[id] = it })

    fun complete(id: String, outcome: AckOutcome) {
        waiters.remove(id)?.complete(outcome)
    }

    fun forget(id: String) {
        waiters.remove(id)
    }

    val size: Int get() = waiters.size

    suspend fun await(id: String, waiter: CompletableDeferred<AckOutcome>, timeoutMs: Long): AckOutcome =
        withTimeoutOrNull(timeoutMs) { waiter.await() } ?: run {
            waiters.remove(id)
            AckOutcome.Timeout
        }
}

internal class AckWaiter(
    private val registry: AckRegistry,
    val id: String,
    private val deferred: CompletableDeferred<AckOutcome>,
) : AutoCloseable {
    suspend fun await(timeoutMs: Long = Limits.ACK_TIMEOUT_MS): AckOutcome = registry.await(id, deferred, timeoutMs)

    override fun close() = registry.forget(id)
}

/**
 * One gate per peer, dropped only when its last user leaves. Removing it while a waiter still
 * held it let a third caller build a second gate and run the very thing the gate serialises.
 */
internal class PeerGates {
    private class Gate {
        val mutex = Mutex()
        var users = 0
    }

    private val gates = java.util.concurrent.ConcurrentHashMap<String, Gate>()

    val size: Int get() = gates.size

    suspend fun <T> withGate(key: String, block: suspend () -> T): T {
        // compute holds the bin lock, so acquiring a gate and counting into it is one step
        val gate = checkNotNull(gates.compute(key) { _, existing -> (existing ?: Gate()).also { it.users++ } })
        try {
            return gate.mutex.withLock { block() }
        } finally {
            gates.compute(key) { _, existing -> existing?.takeIf { --it.users > 0 } }
        }
    }
}

internal class Traffic {
    val sent = AtomicLong()
    val received = AtomicLong()
}

/**
 * TCP on the nebula overlay: one listener on `[::]:msgPort` plus outbound connections. A peer's
 * identity comes from the socket address, which nebula authenticated — never from what the
 * envelope claims about itself.
 */
internal class Transport(private val engine: Engine) {
    private val generations = AtomicLong(1)

    /**
     * Binds before returning, then accepts in the background. The bind is synchronous on purpose:
     * a caller that has been told the engine started may immediately connect to it, and binding
     * inside the coroutine left a window where the port was not open yet.
     */
    fun startListener() {
        val port = engine.profile.msgPort
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(engine.profile.bindHost), port))
            }
        } catch (e: IOException) {
            CoreLog.warn(TAG, "listener bind failed on [${engine.profile.bindHost}]:$port: ${e.message}")
            engine.events.fault("Messaging can't listen on port $port", e.describe())
            return
        }
        engine.onStop { runCatching { server.close() } }
        CoreLog.info(TAG, "listening on [${engine.profile.bindHost}]:$port")
        engine.scope.launch {
            while (isActive) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    if (!isActive || server.isClosed) return@launch
                    CoreLog.warn(TAG, "accept error: ${e.message}")
                    delay(ACCEPT_BACKOFF_MS)
                    continue
                }
                // a connection accepted while the engine is stopping has nobody to serve it
                runCatching { attach(socket, Ip.normalize(socket.inetAddress?.hostAddress)) }.onFailure { runCatching { socket.close() } }
            }
        }
    }

    /** Registers the connection's outbound queue and starts its reader and writer. */
    private fun attach(socket: Socket, hintIp: String): PeerLink {
        // a socket the engine can no longer serve would otherwise stay open with nobody to close it
        if (!engine.scope.isActive) {
            runCatching { socket.close() }
            throw CoreException.unreachable("engine stopped")
        }
        runCatching { socket.tcpNoDelay = true }
        val link = PeerLink(hintIp, generations.getAndIncrement(), socket)
        if (hintIp.isNotEmpty()) remember(hintIp, link)

        engine.scope.launch {
            // inside the try: a link left open with no writer blocks every send on it forever
            try {
                val output = socket.getOutputStream()
                for (frame in link.outbound) {
                    // a blocked write cannot be interrupted; closing the socket is the one thing that ends it
                    val watchdog = launch {
                        delay(Limits.WRITE_TIMEOUT_MS)
                        link.close()
                    }
                    try {
                        output.write(frame)
                        output.flush()
                    } finally {
                        watchdog.cancel()
                    }
                }
            } catch (_: IOException) {
                // the peer went away mid-write; the reader closes the link
            } finally {
                link.close()
            }
        }

        engine.scope.launch { readLoop(socket, link, hintIp) }
        return link
    }

    private suspend fun readLoop(socket: Socket, link: PeerLink, hintIp: String) {
        var peerIp = hintIp
        val parser = FrameParser()
        val buffer = ByteArray(Limits.READ_BUFFER_BYTES)
        try {
            val input = socket.getInputStream()
            while (engine.scope.isActive) {
                val read = runCatching { input.read(buffer) }.getOrDefault(-1)
                if (read <= 0) break
                if (peerIp.isNotEmpty()) engine.countReceived(peerIp, read)
                val envelopes = try {
                    parser.feed(buffer, read)
                } catch (e: CoreException) {
                    CoreLog.warn(TAG, "protocol violation from $peerIp: ${e.message}")
                    break
                }
                for (envelope in envelopes) {
                    // trust the socket address (nebula-authenticated) over the envelope's claim
                    val fromIp = if (peerIp.isEmpty()) Ip.normalize(envelope.from.ip) else peerIp
                    if (peerIp.isEmpty() && fromIp.isNotEmpty()) {
                        peerIp = fromIp
                        remember(peerIp, link)
                    }
                    try {
                        engine.inbound.handle(envelope, fromIp, link)
                    } catch (e: Exception) {
                        CoreLog.warn(TAG, "envelope handling failed: ${e.message}")
                        engine.events.fault("A ${envelope.type.name.lowercase()} frame from $fromIp could not be processed", e.describe())
                    }
                }
            }
        } finally {
            link.close()
            // deregister only when this connection still owns the slot
            if (peerIp.isNotEmpty()) {
                engine.peers.computeIfPresent(peerIp) { _, current ->
                    if (current.generation == link.generation) null else current
                }
            }
        }
    }

    private fun remember(ip: String, link: PeerLink) {
        engine.peers.put(ip, link)?.takeIf { it.generation != link.generation }?.close()
    }

    /**
     * Forgets the peer's cached connection and closes it. A TCP session to a peer whose tunnel
     * went away is a zombie: writes still succeed locally and no reset ever comes back, so the
     * only way to reach the peer again is to drop the socket and connect fresh.
     */
    fun evict(ip: String) {
        engine.peers.remove(Ip.normalize(ip))?.close()
    }

    /** Peers with a live outbound queue right now. */
    fun connectedPeers(): List<String> = engine.peers.entries.filter { it.value.isAlive }.map { it.key }

    private fun cached(ip: String): PeerLink? = engine.peers[ip]?.takeIf { it.isAlive }

    /**
     * The peer's live outbound queue, connecting when needed. Only one connect runs per peer: a
     * burst of concurrent sends (ICE candidates) waits for it and reuses that socket rather than
     * each opening its own.
     */
    suspend fun link(peerIp: String, timeoutMs: Long? = null): PeerLink {
        val ip = Ip.normalize(peerIp)
        if (ip.isEmpty()) throw CoreException.unreachable("empty peer ip")
        cached(ip)?.let { return it }

        return engine.connecting.withGate(ip) {
            cached(ip) ?: connect(ip, timeoutMs ?: Limits.CONNECT_TIMEOUT_MS)
        }
    }

    private suspend fun connect(ip: String, timeoutMs: Long): PeerLink {
        val socket = withContext(Dispatchers.IO) {
            Socket().also {
                try {
                    if (engine.profile.bindHost != WILDCARD) {
                        it.bind(InetSocketAddress(InetAddress.getByName(engine.profile.bindHost), 0))
                    }
                    it.connect(InetSocketAddress(InetAddress.getByName(ip), engine.profile.msgPort), timeoutMs.toInt())
                } catch (e: IOException) {
                    runCatching { it.close() }
                    throw CoreException.unreachable(e.message ?: "connect timeout")
                }
            }
        }
        val link = attach(socket, ip)
        val hello = envelope(EnvelopeType.HELLO).copy(app = engine.profile.appVersion)
        send(link, hello)
        return link
    }

    fun envelope(type: EnvelopeType): Envelope = Envelope(
        v = FrameCodec.PROTOCOL_VERSION,
        type = type,
        id = engine.newId(),
        from = EnvelopeFrom(engine.profile.overlayIp, engine.profile.displayName),
        ts = System.currentTimeMillis(),
    )

    /** Queues one envelope on a link; false when the connection is already gone. */
    suspend fun send(link: PeerLink, envelope: Envelope): Boolean {
        val frame = try {
            FrameCodec.encode(envelope)
        } catch (e: CoreException) {
            CoreLog.warn(TAG, "not sending ${envelope.type}: ${e.message}")
            return false
        }
        return sendFrame(link, frame)
    }

    suspend fun sendFrame(link: PeerLink, frame: ByteArray): Boolean = try {
        link.outbound.send(frame)
        engine.countSent(link.ip, frame.size)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Queues one envelope without waiting: false when the queue is full or the link is gone. This
     * is what the reader answers with, because a reader that waits on its own peer's outbound
     * queue while that peer waits on ours is a deadlock; every reply sent this way is one the
     * peer re-requests on its own.
     */
    fun offer(link: PeerLink, envelope: Envelope): Boolean {
        val frame = try {
            FrameCodec.encode(envelope)
        } catch (e: CoreException) {
            CoreLog.warn(TAG, "not sending ${envelope.type}: ${e.message}")
            return false
        }
        val queued = link.outbound.trySend(frame).isSuccess
        if (queued) engine.countSent(link.ip, frame.size)
        return queued
    }

    /** Acknowledges one envelope back down the connection it arrived on. */
    fun sendAck(link: PeerLink, envelopeId: String) {
        offer(link, envelope(EnvelopeType.MSG_ACK).copy(ackId = envelopeId))
    }


    // --- probes ---

    /**
     * Reachability probe: the round-trip in ms once the peer's pong arrives (which also updates
     * its last-seen), or -1 when it stays silent. A peer that cannot answer in time is treated as
     * gone: its cached connection is evicted, and when the failed probe went over a cached
     * connection one more probe runs over a fresh one — so a single ping recovers from a zombie
     * socket by itself. This is the probe the user asks for, where recovering from a zombie is the
     * whole point; the scheduler uses [probe], which does not pay for the second attempt.
     */
    suspend fun ping(peerIp: String, timeoutMs: Long?): Long {
        val ip = Ip.normalize(peerIp)
        val hadCached = cached(ip) != null
        pingOnce(ip, timeoutMs)?.let { return it }
        evict(ip)
        if (!hadCached) return UNREACHABLE
        return pingOnce(ip, timeoutMs) ?: run {
            evict(ip)
            UNREACHABLE
        }
    }

    /**
     * One probe, for the scheduler. Doubling every probe would double the cost of every silent peer
     * on every rung, and the drain that follows opens the link anyway.
     */
    suspend fun probe(peerIp: String): Long {
        val ip = Ip.normalize(peerIp)
        return pingOnce(ip, Limits.PING_TIMEOUT_MS) ?: run {
            evict(ip)
            UNREACHABLE
        }
    }

    /**
     * A pong carries no reference to its ping, so waiters are keyed by peer — which means two
     * probes to one peer would overwrite each other's waiter and one of them would report a
     * reachable peer as silent. A probe already in flight is therefore shared rather than
     * duplicated: the second caller awaits the first one's answer.
     */
    private suspend fun pingOnce(ip: String, timeoutMs: Long?): Long? {
        val key = pongKey(ip)
        return engine.probing.withGate(ip) {
            val link = runCatching { link(ip, timeoutMs) }.getOrNull() ?: return@withGate null
            engine.acks.register(key).use { waiter ->
                val startedAt = System.nanoTime()
                if (!send(link, envelope(EnvelopeType.PING))) return@withGate null
                val outcome = waiter.await(timeoutMs ?: Limits.PING_TIMEOUT_MS)
                if (outcome == AckOutcome.Ack) (System.nanoTime() - startedAt) / 1_000_000 else null
            }
        }
    }

    /** Delivers one signalling envelope; throws when the peer cannot be reached. */
    suspend fun sendSignal(peerIp: String, signal: OutboundSignal, timeoutMs: Long?) {
        val link = link(peerIp, timeoutMs)
        val envelope = envelope(signal.type).copy(
            id = signal.id ?: engine.newId(),
            callId = signal.callId,
            sdp = signal.sdp,
            sdpType = signal.sdpType,
            video = signal.video,
            candidate = signal.candidate,
            reason = signal.reason,
            typing = signal.typing,
        )
        if (!send(link, envelope)) throw CoreException.unreachable("connection closed")
    }

    companion object {
        const val UNREACHABLE = -1L
        private const val TAG = "TnTransport"
        private const val ACCEPT_BACKOFF_MS = 250L
        private const val WILDCARD = "::"


        /**
         * A pong carries no reference to its ping (v1 of the wire), so the waiter is keyed by peer
         * instead — one outstanding probe per peer, kept apart from envelope ids so it can never
         * collide with a message ack.
         */
        fun pongKey(peerIp: String): String = "pong:$peerIp"
    }
}

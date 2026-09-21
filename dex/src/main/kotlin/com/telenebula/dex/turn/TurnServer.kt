package com.telenebula.dex.turn

import com.telenebula.dex.Limits
import com.telenebula.dex.TurnAccess
import com.telenebula.dex.TurnCredential
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface TurnState {
    data object Off : TurnState
    data class Running(val port: Int) : TurnState
    data class Failed(val message: String) : TurnState
}

/**
 * A minimal TURN relay over UDP (RFC 5766) for one purpose: a browser on the LAN cannot reach the
 * nebula overlay, so its media is relayed through a socket bound to this phone's overlay address.
 * Long-term credentials only, UDP transport only, one allocation per browser socket, everything
 * bounded by [Limits]. Requests from the overlay itself are refused: a peer is never a client.
 */
class TurnServer(
    private val scope: CoroutineScope,
    private val configuredPort: Int,
    private val relayAddress: () -> InetAddress?,
    private val isOverlayAddress: (InetAddress) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val onFault: (String) -> Unit = {},
) : TurnAccess {
    private val mutableState = MutableStateFlow<TurnState>(TurnState.Off)
    val state: StateFlow<TurnState> = mutableState.asStateFlow()

    /** The port actually bound (a configured 0 picks one), else the configured one. */
    override val port: Int get() = synchronized(lock) { socket?.localPort ?: configuredPort }

    private val lock = Any()
    private var socket: DatagramSocket? = null
    private var job: Job? = null
    private var runScope: CoroutineScope? = null

    private class Credential(val key: ByteArray, val expiresAt: Long)
    private val credentials = LinkedHashMap<String, Credential>()

    private class Permission(var expiresAt: Long)
    private class ChannelBinding(val peer: InetSocketAddress, var expiresAt: Long)

    private class Allocation(
        val client: InetSocketAddress,
        val username: String,
        val relay: DatagramSocket,
        val relayed: InetSocketAddress,
        var expiresAt: Long,
    ) {
        val permissions = HashMap<InetAddress, Permission>()
        val channels = HashMap<Int, ChannelBinding>()
        val channelByPeer = HashMap<InetSocketAddress, Int>()
        var reader: Job? = null
    }

    private val allocations = HashMap<InetSocketAddress, Allocation>()

    /** Nonces rotate each window; the previous window's stay valid so a request in flight is not bounced (RFC 5389 §10.2). */
    private var nonceSeed = ByteArray(16).also(random::nextBytes)

    /** Binds the UDP socket; a bind failure is published as [TurnState.Failed], never thrown. */
    fun start() {
        synchronized(lock) {
            if (socket != null) return
            val s = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(configuredPort))
                }
            } catch (e: IOException) {
                mutableState.value = TurnState.Failed("TURN relay could not listen on UDP $configuredPort: ${e.message}")
                return
            } catch (e: SecurityException) {
                mutableState.value = TurnState.Failed("TURN relay could not listen on UDP $configuredPort: ${e.message}")
                return
            }
            socket = s
            val run = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            runScope = run
            job = run.launch { receiveLoop(s) }
            run.launch { sweepLoop() }
            mutableState.value = TurnState.Running(s.localPort)
        }
    }

    /** Closes every relay and the listening socket; idempotent. */
    fun stop() {
        val toClose: List<Allocation>
        synchronized(lock) {
            val s = socket ?: return
            socket = null
            toClose = allocations.values.toList()
            allocations.clear()
            runScope?.cancel()
            runScope = null
            job = null
            s.close()
            mutableState.value = TurnState.Off
        }
        for (a in toClose) a.relay.close()
    }

    override fun issue(): TurnCredential {
        val username = randomToken(12)
        val password = randomToken(24)
        val key = Stun.longTermKey(username, REALM, password)
        synchronized(lock) {
            purgeCredentials(now())
            while (credentials.size >= MAX_CREDENTIALS) credentials.remove(credentials.keys.first())
            credentials[username] = Credential(key, now() + Limits.TURN_CREDENTIAL_MS)
        }
        return TurnCredential(username, password)
    }

    /** For tests and the Dex page: live allocations. */
    val allocationCount: Int get() = synchronized(lock) { allocations.size }

    // --- receiving ------------------------------------------------------------------------------

    private suspend fun receiveLoop(s: DatagramSocket) = withContext(io) {
        val buffer = ByteArray(Limits.MAX_UDP_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!s.isClosed) {
            packet.setData(buffer, 0, buffer.size)
            try {
                s.receive(packet)
            } catch (e: IOException) {
                if (s.isClosed) return@withContext
                continue
            }
            val from = packet.socketAddress as? InetSocketAddress ?: continue
            if (isOverlayAddress(from.address)) continue
            try {
                handle(s, from, buffer, packet.length)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onFault("TURN relay: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun handle(s: DatagramSocket, from: InetSocketAddress, bytes: ByteArray, length: Int) {
        if (length < 4) return
        if (Stun.isChannelData(bytes[0])) {
            handleChannelData(from, bytes, length)
            return
        }
        if (!Stun.isStun(bytes[0])) return
        val message = Stun.parse(bytes, length) ?: return
        when {
            message.method == Stun.METHOD_BINDING && message.isRequest -> {
                send(s, from, Stun.Builder(Stun.METHOD_BINDING, Stun.CLASS_SUCCESS, message.transactionId).xorAddress(Stun.ATTR_XOR_MAPPED_ADDRESS, from).software().build())
            }
            message.method == Stun.METHOD_SEND && message.isIndication -> handleSend(from, message)
            message.isRequest -> handleAuthenticated(s, from, message, bytes, length)
            else -> Unit
        }
    }

    private fun handleChannelData(from: InetSocketAddress, bytes: ByteArray, length: Int) {
        val channel = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val dataLength = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        if (dataLength + 4 > length) return
        val allocation: Allocation
        val binding: ChannelBinding
        synchronized(lock) {
            allocation = allocations[from] ?: return
            binding = allocation.channels[channel] ?: return
            if (binding.expiresAt < now()) return
        }
        relaySend(allocation, binding.peer, bytes, 4, dataLength)
    }

    private fun handleSend(from: InetSocketAddress, message: Stun.Message) {
        val peerBytes = message.first(Stun.ATTR_XOR_PEER_ADDRESS) ?: return
        val data = message.first(Stun.ATTR_DATA) ?: return
        val peer = Stun.decodeXorAddress(peerBytes, message.transactionId) ?: return
        val allocation: Allocation
        synchronized(lock) {
            allocation = allocations[from] ?: return
            val permission = allocation.permissions[peer.address] ?: return
            if (permission.expiresAt < now()) return
        }
        relaySend(allocation, peer, data, 0, data.size)
    }

    private fun relaySend(allocation: Allocation, peer: InetSocketAddress, data: ByteArray, offset: Int, length: Int) {
        try {
            allocation.relay.send(DatagramPacket(data, offset, length, peer))
        } catch (e: IOException) {
            // a closed relay or an unroutable peer is not the server's failure; the browser's ICE agent notices
        }
    }

    // --- authenticated requests -------------------------------------------------------------

    private fun handleAuthenticated(s: DatagramSocket, from: InetSocketAddress, message: Stun.Message, bytes: ByteArray, length: Int) {
        val method = message.method
        if (method != Stun.METHOD_ALLOCATE && method != Stun.METHOD_REFRESH && method != Stun.METHOD_CREATE_PERMISSION && method != Stun.METHOD_CHANNEL_BIND) {
            send(s, from, error(message, 400, "Bad Request").build())
            return
        }
        val stamp = now()
        val integrity = message.first(Stun.ATTR_MESSAGE_INTEGRITY)
        val username = message.first(Stun.ATTR_USERNAME)?.toString(Charsets.UTF_8)
        val realm = message.first(Stun.ATTR_REALM)?.toString(Charsets.UTF_8)
        val nonce = message.first(Stun.ATTR_NONCE)?.toString(Charsets.UTF_8)
        if (integrity == null || username == null || realm == null || nonce == null) {
            send(s, from, challenge(message, 401, "Unauthorized", stamp))
            return
        }
        if (realm != REALM) {
            send(s, from, challenge(message, 401, "Unauthorized", stamp))
            return
        }
        if (!isValidNonce(nonce)) {
            send(s, from, challenge(message, 438, "Stale Nonce", stamp))
            return
        }
        val key: ByteArray? = synchronized(lock) {
            purgeCredentials(stamp)
            credentials[username]?.key
        }
        if (key == null || !Stun.verifyIntegrity(bytes, length, key)) {
            send(s, from, challenge(message, 401, "Unauthorized", stamp))
            return
        }
        val response = when (method) {
            Stun.METHOD_ALLOCATE -> allocate(from, message, username, stamp)
            Stun.METHOD_REFRESH -> refresh(from, message, username, stamp)
            Stun.METHOD_CREATE_PERMISSION -> createPermission(from, message, username, stamp)
            else -> channelBind(from, message, username, stamp)
        }
        send(s, from, response.build(key))
    }

    private fun allocate(from: InetSocketAddress, message: Stun.Message, username: String, stamp: Long): Stun.Builder {
        val transport = message.first(Stun.ATTR_REQUESTED_TRANSPORT)?.let { Stun.readInt(it) }
        if (transport == null) return error(message, 400, "Bad Request")
        if ((transport ushr 24) != Stun.TRANSPORT_UDP) return error(message, 442, "Unsupported Transport Protocol")
        if (message.first(Stun.ATTR_EVEN_PORT) != null) return error(message, 508, "Insufficient Capacity")
        val lifetime = requestedLifetime(message)
        val relayHost = relayAddress() ?: return error(message, 508, "Insufficient Capacity")
        synchronized(lock) {
            val existing = allocations[from]
            if (existing != null) {
                // the same request retransmitted is answered again; a new one on a live 5-tuple is a mistake
                return if (existing.username == username && existing.expiresAt >= stamp) success(message, existing, lifetime) else error(message, 437, "Allocation Mismatch")
            }
            if (allocations.size >= Limits.MAX_TURN_ALLOCATIONS) return error(message, 486, "Allocation Quota Reached")
        }
        val relay = try {
            DatagramSocket(0, relayHost)
        } catch (e: IOException) {
            return error(message, 508, "Insufficient Capacity")
        } catch (e: SecurityException) {
            return error(message, 508, "Insufficient Capacity")
        }
        val relayed = InetSocketAddress(relayHost, relay.localPort)
        val allocation = Allocation(from, username, relay, relayed, stamp + lifetime * 1000L)
        val run = synchronized(lock) {
            val raced = allocations[from]
            if (raced != null || allocations.size >= Limits.MAX_TURN_ALLOCATIONS) {
                null
            } else {
                allocations[from] = allocation
                runScope
            }
        }
        if (run == null) {
            relay.close()
            return error(message, 486, "Allocation Quota Reached")
        }
        allocation.reader = run.launch { relayLoop(allocation) }
        return success(message, allocation, lifetime)
    }

    private fun success(message: Stun.Message, allocation: Allocation, lifetime: Int): Stun.Builder =
        Stun.Builder(Stun.METHOD_ALLOCATE, Stun.CLASS_SUCCESS, message.transactionId)
            .xorAddress(Stun.ATTR_XOR_RELAYED_ADDRESS, allocation.relayed)
            .addInt(Stun.ATTR_LIFETIME, lifetime)
            .xorAddress(Stun.ATTR_XOR_MAPPED_ADDRESS, allocation.client)
            .software()

    private fun refresh(from: InetSocketAddress, message: Stun.Message, username: String, stamp: Long): Stun.Builder {
        val lifetime = message.first(Stun.ATTR_LIFETIME)?.let { Stun.readInt(it) }
        val wanted = if (lifetime == 0) 0 else requestedLifetime(message)
        val removed: Allocation?
        synchronized(lock) {
            val allocation = allocations[from] ?: return error(message, 437, "Allocation Mismatch")
            if (allocation.username != username) return error(message, 441, "Wrong Credentials")
            if (wanted == 0) {
                allocations.remove(from)
                removed = allocation
            } else {
                allocation.expiresAt = stamp + wanted * 1000L
                removed = null
            }
        }
        removed?.let(::closeAllocation)
        return Stun.Builder(Stun.METHOD_REFRESH, Stun.CLASS_SUCCESS, message.transactionId).addInt(Stun.ATTR_LIFETIME, wanted).software()
    }

    private fun createPermission(from: InetSocketAddress, message: Stun.Message, username: String, stamp: Long): Stun.Builder {
        val peers = message.all(Stun.ATTR_XOR_PEER_ADDRESS).map { Stun.decodeXorAddress(it, message.transactionId) ?: return error(message, 400, "Bad Request") }
        if (peers.isEmpty()) return error(message, 400, "Bad Request")
        synchronized(lock) {
            val allocation = allocations[from] ?: return error(message, 437, "Allocation Mismatch")
            if (allocation.username != username) return error(message, 441, "Wrong Credentials")
            for (peer in peers) {
                if (peer.address.address.size != allocation.relayed.address.address.size) return error(message, 443, "Peer Address Family Mismatch")
            }
            for (peer in peers) {
                val existing = allocation.permissions[peer.address]
                if (existing != null) {
                    existing.expiresAt = stamp + Limits.TURN_PERMISSION_MS
                } else {
                    if (allocation.permissions.size >= Limits.MAX_TURN_PERMISSIONS) return error(message, 508, "Insufficient Capacity")
                    allocation.permissions[peer.address] = Permission(stamp + Limits.TURN_PERMISSION_MS)
                }
            }
        }
        return Stun.Builder(Stun.METHOD_CREATE_PERMISSION, Stun.CLASS_SUCCESS, message.transactionId).software()
    }

    private fun channelBind(from: InetSocketAddress, message: Stun.Message, username: String, stamp: Long): Stun.Builder {
        val channel = message.first(Stun.ATTR_CHANNEL_NUMBER)?.let { Stun.readInt(it) }?.let { it ushr 16 } ?: return error(message, 400, "Bad Request")
        val peerBytes = message.first(Stun.ATTR_XOR_PEER_ADDRESS) ?: return error(message, 400, "Bad Request")
        val peer = Stun.decodeXorAddress(peerBytes, message.transactionId) ?: return error(message, 400, "Bad Request")
        if (!Stun.isChannelNumber(channel)) return error(message, 400, "Bad Request")
        synchronized(lock) {
            val allocation = allocations[from] ?: return error(message, 437, "Allocation Mismatch")
            if (allocation.username != username) return error(message, 441, "Wrong Credentials")
            if (peer.address.address.size != allocation.relayed.address.address.size) return error(message, 443, "Peer Address Family Mismatch")
            val bound = allocation.channels[channel]
            val boundChannel = allocation.channelByPeer[peer]
            if (bound != null && bound.peer != peer) return error(message, 400, "Bad Request")
            if (boundChannel != null && boundChannel != channel) return error(message, 400, "Bad Request")
            if (bound == null && allocation.channels.size >= Limits.MAX_TURN_CHANNELS) return error(message, 508, "Insufficient Capacity")
            val permission = allocation.permissions[peer.address]
            if (permission == null && allocation.permissions.size >= Limits.MAX_TURN_PERMISSIONS) return error(message, 508, "Insufficient Capacity")
            allocation.channels[channel] = ChannelBinding(peer, stamp + Limits.TURN_CHANNEL_MS)
            allocation.channelByPeer[peer] = channel
            if (permission != null) permission.expiresAt = stamp + Limits.TURN_PERMISSION_MS else allocation.permissions[peer.address] = Permission(stamp + Limits.TURN_PERMISSION_MS)
        }
        return Stun.Builder(Stun.METHOD_CHANNEL_BIND, Stun.CLASS_SUCCESS, message.transactionId).software()
    }

    private fun requestedLifetime(message: Stun.Message): Int {
        val asked = message.first(Stun.ATTR_LIFETIME)?.let { Stun.readInt(it) } ?: return Limits.DEFAULT_TURN_LIFETIME_S
        return when {
            asked <= 0 -> Limits.DEFAULT_TURN_LIFETIME_S
            asked > Limits.MAX_TURN_LIFETIME_S -> Limits.MAX_TURN_LIFETIME_S
            else -> asked
        }
    }

    // --- relaying peer data to the browser ----------------------------------------------------

    private suspend fun relayLoop(allocation: Allocation) = withContext(io) {
        val buffer = ByteArray(Limits.MAX_UDP_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!allocation.relay.isClosed) {
            packet.setData(buffer, 0, buffer.size)
            try {
                allocation.relay.receive(packet)
            } catch (e: IOException) {
                if (allocation.relay.isClosed) return@withContext
                continue
            }
            val peer = packet.socketAddress as? InetSocketAddress ?: continue
            val channel: Int?
            synchronized(lock) {
                val permission = allocation.permissions[peer.address] ?: continue
                if (permission.expiresAt < now()) continue
                channel = allocation.channelByPeer[peer]?.takeIf { c -> (allocation.channels[c]?.expiresAt ?: 0L) >= now() }
            }
            val out = if (channel != null) {
                Stun.channelData(channel, buffer, 0, packet.length)
            } else {
                Stun.Builder(Stun.METHOD_DATA, Stun.CLASS_INDICATION, transactionId())
                    .xorAddress(Stun.ATTR_XOR_PEER_ADDRESS, peer)
                    .add(Stun.ATTR_DATA, buffer.copyOfRange(0, packet.length))
                    .build(withFingerprint = false)
            }
            val s = synchronized(lock) { socket } ?: return@withContext
            send(s, allocation.client, out)
        }
    }

    // --- housekeeping -------------------------------------------------------------------------

    private suspend fun sweepLoop() {
        while (true) {
            delay(SWEEP_MS)
            sweep(now())
        }
    }

    /** Drops what has expired; public so a test can drive time without the ticker. */
    fun sweep(stamp: Long) {
        val expired = ArrayList<Allocation>()
        synchronized(lock) {
            val it = allocations.values.iterator()
            while (it.hasNext()) {
                val a = it.next()
                if (a.expiresAt < stamp) {
                    it.remove()
                    expired.add(a)
                    continue
                }
                a.permissions.values.removeAll { p -> p.expiresAt < stamp }
                val dead = a.channels.filterValues { c -> c.expiresAt < stamp }
                for ((channel, binding) in dead) {
                    a.channels.remove(channel)
                    a.channelByPeer.remove(binding.peer)
                }
            }
            purgeCredentials(stamp)
            if (stamp / NONCE_WINDOW_MS != lastNonceWindow) {
                previousNonceSeed = nonceSeed
                nonceSeed = ByteArray(16).also(random::nextBytes)
                lastNonceWindow = stamp / NONCE_WINDOW_MS
            }
        }
        for (a in expired) closeAllocation(a)
    }

    private fun closeAllocation(allocation: Allocation) {
        allocation.reader?.cancel()
        allocation.relay.close()
    }

    private fun purgeCredentials(stamp: Long) {
        val it = credentials.values.iterator()
        while (it.hasNext()) if (it.next().expiresAt < stamp) it.remove()
    }

    private var previousNonceSeed: ByteArray = nonceSeed
    private var lastNonceWindow: Long = 0

    private fun currentNonce(): String = synchronized(lock) { hex(nonceSeed) }

    private fun isValidNonce(nonce: String): Boolean = synchronized(lock) {
        nonce == hex(nonceSeed) || nonce == hex(previousNonceSeed)
    }

    private fun challenge(message: Stun.Message, code: Int, reason: String, stamp: Long): ByteArray =
        Stun.Builder(message.method, Stun.CLASS_ERROR, message.transactionId)
            .errorCode(code, reason)
            .addString(Stun.ATTR_REALM, REALM)
            .addString(Stun.ATTR_NONCE, currentNonce())
            .software()
            .build()

    private fun error(message: Stun.Message, code: Int, reason: String): Stun.Builder =
        Stun.Builder(message.method, Stun.CLASS_ERROR, message.transactionId).errorCode(code, reason).software()

    private fun send(s: DatagramSocket, to: InetSocketAddress, bytes: ByteArray) {
        try {
            s.send(DatagramPacket(bytes, bytes.size, to))
        } catch (e: IOException) {
            // the browser socket may be gone; nothing to tell anyone
        }
    }

    private fun transactionId(): ByteArray = ByteArray(Stun.TRANSACTION_BYTES).also(random::nextBytes)

    private fun randomToken(bytes: Int): String = hex(ByteArray(bytes).also(random::nextBytes))

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            out.append(HEX[(b.toInt() shr 4) and 0xF])
            out.append(HEX[b.toInt() and 0xF])
        }
        return out.toString()
    }

    companion object {
        const val REALM = "telenebula"
        /** Credentials outstanding at once: one per call per browser, with slack for reconnects. */
        const val MAX_CREDENTIALS = 32
        const val SWEEP_MS = 5_000L
        /** Nonces are good for this long plus one more window (RFC 5389 §10.2 leaves the policy to the server). */
        const val NONCE_WINDOW_MS = 60L * 60 * 1000
        private const val HEX = "0123456789abcdef"
    }
}

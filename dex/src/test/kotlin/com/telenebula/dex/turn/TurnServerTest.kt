package com.telenebula.dex.turn

import com.telenebula.dex.Limits
import com.telenebula.dex.TurnCredential
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A browser-shaped client and a peer-shaped socket, both on loopback, through a real relay. */
class TurnServerTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_000_000L
    private lateinit var server: TurnServer
    private lateinit var client: DatagramSocket
    private lateinit var peer: DatagramSocket
    private lateinit var serverAddress: InetSocketAddress

    @Before
    fun start() {
        server = TurnServer(scope, configuredPort = 0, relayAddress = { loopback }, isOverlayAddress = { false }, now = { clock })
        server.start()
        assertTrue(server.state.value is TurnState.Running)
        serverAddress = InetSocketAddress(loopback, server.port)
        client = DatagramSocket(0, loopback).apply { soTimeout = 2_000 }
        peer = DatagramSocket(0, loopback).apply { soTimeout = 2_000 }
    }

    @After
    fun stop() {
        server.stop()
        client.close()
        peer.close()
        scope.cancel()
    }

    private var txCounter = 0
    private fun txid(): ByteArray = ByteArray(12).also { it[11] = (++txCounter).toByte() }

    private fun send(socket: DatagramSocket, to: InetSocketAddress, bytes: ByteArray) = socket.send(DatagramPacket(bytes, bytes.size, to))

    private fun receive(socket: DatagramSocket): ByteArray? {
        val buffer = ByteArray(Limits.MAX_UDP_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            buffer.copyOfRange(0, packet.length)
        } catch (e: SocketTimeoutException) {
            null
        }
    }

    private fun receiveStun(socket: DatagramSocket): Stun.Message? = receive(socket)?.let { Stun.parse(it, it.size) }

    private fun errorCodeOf(message: Stun.Message?): Int? = message?.first(Stun.ATTR_ERROR_CODE)?.let { (it[2].toInt() and 0xFF) * 100 + (it[3].toInt() and 0xFF) }

    private class Auth(val username: String, val key: ByteArray, val nonce: String)

    /** Allocate → 401 → learn the nonce, as a browser does. */
    private fun challenge(credential: TurnCredential): Auth {
        send(client, serverAddress, Stun.Builder(Stun.METHOD_ALLOCATE, Stun.CLASS_REQUEST, txid()).addInt(Stun.ATTR_REQUESTED_TRANSPORT, Stun.TRANSPORT_UDP shl 24).build())
        val reply = receiveStun(client)
        assertEquals(401, errorCodeOf(reply))
        assertEquals(TurnServer.REALM, reply?.first(Stun.ATTR_REALM)?.toString(Charsets.UTF_8))
        val nonce = reply?.first(Stun.ATTR_NONCE)?.toString(Charsets.UTF_8)
        assertNotNull(nonce)
        return Auth(credential.username, Stun.longTermKey(credential.username, TurnServer.REALM, credential.password), nonce.orEmpty())
    }

    private fun authed(method: Int, auth: Auth, fill: Stun.Builder.() -> Unit = {}): ByteArray =
        Stun.Builder(method, Stun.CLASS_REQUEST, txid())
            .apply(fill)
            .addString(Stun.ATTR_USERNAME, auth.username)
            .addString(Stun.ATTR_REALM, TurnServer.REALM)
            .addString(Stun.ATTR_NONCE, auth.nonce)
            .build(auth.key)

    private fun allocate(auth: Auth, socket: DatagramSocket = client): Stun.Message? {
        send(socket, serverAddress, authed(Stun.METHOD_ALLOCATE, auth) { addInt(Stun.ATTR_REQUESTED_TRANSPORT, Stun.TRANSPORT_UDP shl 24) })
        return receiveStun(socket)
    }

    private fun relayedAddress(allocation: Stun.Message?): InetSocketAddress {
        val relayed = allocation?.first(Stun.ATTR_XOR_RELAYED_ADDRESS)?.let { Stun.decodeXorAddress(it, allocation.transactionId) }
        assertNotNull(relayed)
        return relayed ?: error("no relayed address")
    }

    private fun peerAddress(): InetSocketAddress = InetSocketAddress(loopback, peer.localPort)

    @Test
    fun `binding is answered without credentials with the sender's own address`() {
        send(client, serverAddress, Stun.Builder(Stun.METHOD_BINDING, Stun.CLASS_REQUEST, txid()).build())
        val reply = receiveStun(client)
        assertEquals(Stun.CLASS_SUCCESS, reply?.cls)
        val mapped = reply?.first(Stun.ATTR_XOR_MAPPED_ADDRESS)?.let { Stun.decodeXorAddress(it, reply.transactionId) }
        assertEquals(InetSocketAddress(loopback, client.localPort), mapped)
    }

    @Test
    fun `allocate, permission, send and data both ways, then channel binding`() {
        val auth = challenge(server.issue())
        val allocation = allocate(auth)
        assertEquals(Stun.CLASS_SUCCESS, allocation?.cls)
        assertEquals(Limits.DEFAULT_TURN_LIFETIME_S, allocation?.first(Stun.ATTR_LIFETIME)?.let(Stun::readInt))
        assertTrue(allocation?.first(Stun.ATTR_MESSAGE_INTEGRITY) != null)
        val relayed = relayedAddress(allocation)
        assertEquals(1, server.allocationCount)

        // a peer with no permission never reaches the browser; it is a stranger, so no later
        // permission can let a datagram buffered in the relay through and make this flaky
        val stranger = DatagramSocket(0, loopback)
        send(stranger, relayed, byteArrayOf(9))
        assertNull(receive(client))
        stranger.close()

        send(client, serverAddress, authed(Stun.METHOD_CREATE_PERMISSION, auth) { xorAddress(Stun.ATTR_XOR_PEER_ADDRESS, peerAddress()) })
        assertEquals(Stun.CLASS_SUCCESS, receiveStun(client)?.cls)

        // Send indication → peer
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        send(client, serverAddress, Stun.Builder(Stun.METHOD_SEND, Stun.CLASS_INDICATION, txid()).xorAddress(Stun.ATTR_XOR_PEER_ADDRESS, peerAddress()).add(Stun.ATTR_DATA, payload).build(withFingerprint = false))
        val atPeer = receive(peer)
        assertTrue(atPeer.contentEquals(payload))

        // peer → Data indication
        send(peer, relayed, byteArrayOf(7, 7))
        val data = receiveStun(client)
        assertEquals(Stun.METHOD_DATA, data?.method)
        assertEquals(Stun.CLASS_INDICATION, data?.cls)
        assertEquals(peerAddress(), data?.first(Stun.ATTR_XOR_PEER_ADDRESS)?.let { Stun.decodeXorAddress(it, data.transactionId) })
        assertTrue(data?.first(Stun.ATTR_DATA).contentEquals(byteArrayOf(7, 7)))

        // channel binding replaces indications with 4-byte headers
        send(client, serverAddress, authed(Stun.METHOD_CHANNEL_BIND, auth) { addInt(Stun.ATTR_CHANNEL_NUMBER, 0x4000 shl 16); xorAddress(Stun.ATTR_XOR_PEER_ADDRESS, peerAddress()) })
        assertEquals(Stun.CLASS_SUCCESS, receiveStun(client)?.cls)
        send(client, serverAddress, Stun.channelData(0x4000, byteArrayOf(8, 8, 8), 0, 3))
        assertTrue(receive(peer).contentEquals(byteArrayOf(8, 8, 8)))
        send(peer, relayed, byteArrayOf(6))
        val framed = receive(client)
        assertNotNull(framed)
        assertEquals(0x40, framed?.get(0)?.toInt())
        assertEquals(1, framed?.get(3)?.toInt())
        assertEquals(6, framed?.get(4)?.toInt())

        // a channel to another peer with the same number is refused
        val other = DatagramSocket(0, loopback)
        send(client, serverAddress, authed(Stun.METHOD_CHANNEL_BIND, auth) { addInt(Stun.ATTR_CHANNEL_NUMBER, 0x4000 shl 16); xorAddress(Stun.ATTR_XOR_PEER_ADDRESS, InetSocketAddress(loopback, other.localPort)) })
        assertEquals(400, errorCodeOf(receiveStun(client)))
        other.close()

        // refresh with lifetime 0 deletes the allocation and closes its relay
        send(client, serverAddress, authed(Stun.METHOD_REFRESH, auth) { addInt(Stun.ATTR_LIFETIME, 0) })
        assertEquals(Stun.CLASS_SUCCESS, receiveStun(client)?.cls)
        assertEquals(0, server.allocationCount)
        send(peer, relayed, byteArrayOf(1))
        assertNull(receive(client))
    }

    @Test
    fun `wrong password, stale nonce, wrong transport and a second allocate are each refused`() {
        val credential = server.issue()
        val good = challenge(credential)
        val bad = Auth(good.username, Stun.longTermKey(good.username, TurnServer.REALM, "nope"), good.nonce)
        assertEquals(401, errorCodeOf(allocate(bad)))
        assertEquals(438, errorCodeOf(allocate(Auth(good.username, good.key, "stale"))))
        send(client, serverAddress, authed(Stun.METHOD_ALLOCATE, good) { addInt(Stun.ATTR_REQUESTED_TRANSPORT, 6 shl 24) })
        assertEquals(442, errorCodeOf(receiveStun(client)))
        assertEquals(Stun.CLASS_SUCCESS, allocate(good)?.cls)
        // a retransmit is answered again; a different user on the same socket is a mismatch
        assertEquals(Stun.CLASS_SUCCESS, allocate(good)?.cls)
        val second = challenge(server.issue())
        assertEquals(437, errorCodeOf(allocate(second)))
        // refresh and permission with the wrong user: 441
        send(client, serverAddress, authed(Stun.METHOD_REFRESH, second) { addInt(Stun.ATTR_LIFETIME, 100) })
        assertEquals(441, errorCodeOf(receiveStun(client)))
        assertEquals(1, server.allocationCount)
    }

    @Test
    fun `allocations expire on the sweep and the quota holds`() {
        val sockets = ArrayList<DatagramSocket>()
        try {
            for (i in 0 until Limits.MAX_TURN_ALLOCATIONS) {
                val s = DatagramSocket(0, loopback).apply { soTimeout = 2_000 }
                sockets += s
                assertEquals(Stun.CLASS_SUCCESS, allocate(challenge(server.issue()), s)?.cls)
            }
            assertEquals(Limits.MAX_TURN_ALLOCATIONS, server.allocationCount)
            assertEquals(486, errorCodeOf(allocate(challenge(server.issue()))))
            clock += (Limits.DEFAULT_TURN_LIFETIME_S + 1) * 1000L
            server.sweep(clock)
            assertEquals(0, server.allocationCount)
        } finally {
            sockets.forEach { it.close() }
        }
    }

    @Test
    fun `an expired credential no longer authenticates`() {
        val credential = server.issue()
        val auth = challenge(credential)
        clock += Limits.TURN_CREDENTIAL_MS + 1
        assertEquals(401, errorCodeOf(allocate(auth)))
    }

    @Test
    fun `requests from the overlay are ignored and stop closes everything`() {
        server.stop()
        val guarded = TurnServer(scope, configuredPort = 0, relayAddress = { loopback }, isOverlayAddress = { true }, now = { clock })
        guarded.start()
        val address = InetSocketAddress(loopback, guarded.port)
        send(client, address, Stun.Builder(Stun.METHOD_BINDING, Stun.CLASS_REQUEST, txid()).build())
        assertNull(receive(client))
        guarded.stop()
        assertEquals(TurnState.Off, guarded.state.value)
        guarded.stop()
    }
}

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
        server = TurnServer(scope, configuredPort = 0, relayAddress = { loopback }, isClient = { true }, now = { clock })
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

    private fun issue(owner: String = "browser-1", callPeer: InetAddress = loopback): TurnCredential = server.issue(owner, callPeer)

    /** Appends one attribute after the message's end and fixes the length, as an attacker on the path would. */
    private fun append(bytes: ByteArray, type: Int, value: ByteArray): ByteArray {
        val out = bytes + Stun.encodeAttribute(type, value)
        val length = out.size - Stun.HEADER_BYTES
        out[2] = (length shr 8).toByte()
        out[3] = length.toByte()
        return out
    }

    private fun signedOnly(method: Int, auth: Auth, fill: Stun.Builder.() -> Unit): ByteArray =
        Stun.Builder(method, Stun.CLASS_REQUEST, txid())
            .apply(fill)
            .addString(Stun.ATTR_USERNAME, auth.username)
            .addString(Stun.ATTR_REALM, TurnServer.REALM)
            .addString(Stun.ATTR_NONCE, auth.nonce)
            .build(auth.key, withFingerprint = false)

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
        val auth = challenge(issue())
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
        val credential = issue()
        val good = challenge(credential)
        val bad = Auth(good.username, Stun.longTermKey(good.username, TurnServer.REALM, "nope"), good.nonce)
        assertEquals(401, errorCodeOf(allocate(bad)))
        assertEquals(438, errorCodeOf(allocate(Auth(good.username, good.key, "stale"))))
        send(client, serverAddress, authed(Stun.METHOD_ALLOCATE, good) { addInt(Stun.ATTR_REQUESTED_TRANSPORT, 6 shl 24) })
        assertEquals(442, errorCodeOf(receiveStun(client)))
        assertEquals(Stun.CLASS_SUCCESS, allocate(good)?.cls)
        // a retransmit is answered again; a different user on the same socket is a mismatch
        assertEquals(Stun.CLASS_SUCCESS, allocate(good)?.cls)
        val second = challenge(issue())
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
                assertEquals(Stun.CLASS_SUCCESS, allocate(challenge(issue()), s)?.cls)
            }
            assertEquals(Limits.MAX_TURN_ALLOCATIONS, server.allocationCount)
            assertEquals(486, errorCodeOf(allocate(challenge(issue()))))
            clock += (Limits.DEFAULT_TURN_LIFETIME_S + 1) * 1000L
            server.sweep(clock)
            assertEquals(0, server.allocationCount)
        } finally {
            sockets.forEach { it.close() }
        }
    }

    @Test
    fun `an expired credential no longer authenticates`() {
        val credential = issue()
        val auth = challenge(credential)
        clock += Limits.TURN_CREDENTIAL_MS + 1
        assertEquals(401, errorCodeOf(allocate(auth)))
    }

    @Test
    fun `binding answers carry nothing beyond the mapped address and are budgeted`() {
        val reply = run {
            send(client, serverAddress, Stun.Builder(Stun.METHOD_BINDING, Stun.CLASS_REQUEST, txid()).build())
            receiveStun(client)
        }
        assertNull(reply?.first(Stun.ATTR_SOFTWARE))
        repeat(Limits.TURN_UNAUTHENTICATED_BURST + 10) {
            send(client, serverAddress, Stun.Builder(Stun.METHOD_BINDING, Stun.CLASS_REQUEST, txid()).build())
        }
        var answered = 0
        client.soTimeout = 500
        while (receive(client) != null) answered += 1
        assertEquals(Limits.TURN_UNAUTHENTICATED_BURST - 1, answered)
    }

    @Test
    fun `an attribute appended after a valid integrity changes nothing`() {
        val auth = challenge(issue())
        assertEquals(Stun.CLASS_SUCCESS, allocate(auth)?.cls)
        val relayed = relayedAddress(allocate(auth))

        // a lifetime of 0 after the HMAC would delete the allocation if it were read
        send(client, serverAddress, append(signedOnly(Stun.METHOD_REFRESH, auth) { addInt(Stun.ATTR_LIFETIME, 600) }, Stun.ATTR_LIFETIME, byteArrayOf(0, 0, 0, 0)))
        assertNull(receive(client))
        assertEquals(1, server.allocationCount)

        // a peer address after the HMAC would open a permission if it were read
        send(client, serverAddress, append(signedOnly(Stun.METHOD_CREATE_PERMISSION, auth) {}, Stun.ATTR_XOR_PEER_ADDRESS, Stun.encodeXorAddress(peerAddress(), ByteArray(12))))
        assertNull(receive(client))
        send(peer, relayed, byteArrayOf(5))
        assertNull(receive(client))

        // a channel number after the HMAC would bind a channel if it were read
        send(client, serverAddress, append(signedOnly(Stun.METHOD_CHANNEL_BIND, auth) { xorAddress(Stun.ATTR_XOR_PEER_ADDRESS, peerAddress()) }, Stun.ATTR_CHANNEL_NUMBER, byteArrayOf(0x40, 0x00, 0, 0)))
        assertNull(receive(client))
        send(client, serverAddress, Stun.channelData(0x4000, byteArrayOf(1), 0, 1))
        assertNull(receive(peer))

        // the same requests, signed whole and fingerprinted as a browser sends them, still work
        send(client, serverAddress, authed(Stun.METHOD_CREATE_PERMISSION, auth) { xorAddress(Stun.ATTR_XOR_PEER_ADDRESS, peerAddress()) })
        assertEquals(Stun.CLASS_SUCCESS, receiveStun(client)?.cls)
        send(client, serverAddress, authed(Stun.METHOD_REFRESH, auth) { addInt(Stun.ATTR_LIFETIME, 600) })
        assertEquals(600, receiveStun(client)?.first(Stun.ATTR_LIFETIME)?.let(Stun::readInt))
    }

    @Test
    fun `a credential relays only to the peer its call is with`() {
        val auth = challenge(issue(callPeer = InetAddress.getByName("10.42.0.9")))
        assertEquals(Stun.CLASS_SUCCESS, allocate(auth)?.cls)
        send(client, serverAddress, authed(Stun.METHOD_CREATE_PERMISSION, auth) { xorAddress(Stun.ATTR_XOR_PEER_ADDRESS, peerAddress()) })
        assertEquals(403, errorCodeOf(receiveStun(client)))
        send(client, serverAddress, authed(Stun.METHOD_CHANNEL_BIND, auth) { addInt(Stun.ATTR_CHANNEL_NUMBER, 0x4000 shl 16); xorAddress(Stun.ATTR_XOR_PEER_ADDRESS, peerAddress()) })
        assertEquals(403, errorCodeOf(receiveStun(client)))
        send(client, serverAddress, Stun.Builder(Stun.METHOD_SEND, Stun.CLASS_INDICATION, txid()).xorAddress(Stun.ATTR_XOR_PEER_ADDRESS, peerAddress()).add(Stun.ATTR_DATA, byteArrayOf(1)).build(withFingerprint = false))
        assertNull(receive(peer))
    }

    @Test
    fun `revoking a browser closes its relay and its credential stops authenticating`() {
        val auth = challenge(issue(owner = "gone"))
        val relayed = relayedAddress(allocate(auth))
        send(client, serverAddress, authed(Stun.METHOD_CREATE_PERMISSION, auth) { xorAddress(Stun.ATTR_XOR_PEER_ADDRESS, peerAddress()) })
        assertEquals(Stun.CLASS_SUCCESS, receiveStun(client)?.cls)
        server.revoke("someone else")
        assertEquals(1, server.allocationCount)
        server.revoke("gone")
        assertEquals(0, server.allocationCount)
        send(peer, relayed, byteArrayOf(1))
        assertNull(receive(client))
        assertEquals(401, errorCodeOf(allocate(auth)))

        val again = challenge(issue(owner = "next"))
        assertEquals(Stun.CLASS_SUCCESS, allocate(again)?.cls)
        server.revokeAll()
        assertEquals(0, server.allocationCount)
        assertEquals(401, errorCodeOf(allocate(again)))
    }

    @Test
    fun `a restart forgets every credential it issued`() {
        val credential = issue()
        server.stop()
        server.start()
        serverAddress = InetSocketAddress(loopback, server.port)
        val auth = challenge(credential)
        assertEquals(401, errorCodeOf(allocate(auth)))
    }

    @Test
    fun `a relay whose credential is no longer held is closed on the sweep`() {
        val auth = challenge(issue())
        assertEquals(Stun.CLASS_SUCCESS, allocate(auth)?.cls)
        repeat(TurnServer.MAX_CREDENTIALS) { issue(owner = "other-$it") }
        server.sweep(clock)
        assertEquals(0, server.allocationCount)
    }

    @Test
    fun `sources the admission refuses are ignored and stop closes everything`() {
        server.stop()
        val guarded = TurnServer(scope, configuredPort = 0, relayAddress = { loopback }, isClient = { false }, now = { clock })
        guarded.start()
        val address = InetSocketAddress(loopback, guarded.port)
        send(client, address, Stun.Builder(Stun.METHOD_BINDING, Stun.CLASS_REQUEST, txid()).build())
        assertNull(receive(client))
        guarded.stop()
        assertEquals(TurnState.Off, guarded.state.value)
        guarded.stop()
    }
}

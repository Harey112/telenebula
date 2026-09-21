package com.telenebula.dex

import com.telenebula.dex.auth.Passwords
import com.telenebula.dex.http.WsCodec
import com.telenebula.dex.http.WsOpcode
import com.telenebula.dex.wire.DexCallState
import com.telenebula.dex.wire.DexChat
import com.telenebula.dex.wire.DexChatView
import com.telenebula.dex.wire.DexContact
import com.telenebula.dex.wire.DexIdentity
import com.telenebula.dex.wire.DexJson
import com.telenebula.dex.wire.DexMessage
import com.telenebula.dex.wire.DexPresence
import com.telenebula.dex.wire.DexQueue
import com.telenebula.dex.wire.ServerFrame
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ServerSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The whole server over a real loopback socket, with plain TCP standing in for TLS. */
class DexServerTest {
    private class FakeBackend : DexBackend {
        override val me = MutableStateFlow<DexIdentity?>(DexIdentity("Me", "10.42.0.1"))
        override val overlayNetworks = MutableStateFlow(listOf("10.42.0.0/16"))
        val chatList = MutableStateFlow(listOf(DexChat("10.42.0.2", "Bob", unread = 1)))
        val sent = CompletableFuture<String>()
        val uploads = CompletableFuture<DexUpload>()
        val gone = CompletableFuture<String>()
        val attachmentFile = File.createTempFile("dex", ".txt").apply { writeText("0123456789") }
        val uploadDir = File(System.getProperty("java.io.tmpdir"), "dex-test-${System.nanoTime()}")

        override fun chats(): Flow<List<DexChat>> = chatList
        override fun contacts(): Flow<List<DexContact>> = flowOf(emptyList())
        override fun chat(peer: String): Flow<DexChatView> = flowOf(DexChatView(peer, DexContact(peer, "Bob", "Bob"), emptyList(), hasMore = false))
        override suspend fun messagesBefore(peer: String, beforeTs: Long, beforeId: String, limit: Int): List<DexMessage> = emptyList()
        override fun presence(): Flow<Map<String, DexPresence>> = flowOf(emptyMap())
        override fun typing(): Flow<Set<String>> = flowOf(emptySet())
        override fun queues(): Flow<List<DexQueue>> = flowOf(emptyList())
        override suspend fun freeBytes(): Long = 4_096
        override suspend fun sendText(peer: String, body: String, replyTo: String?, isCovered: Boolean) {
            sent.complete("$peer:$body")
        }
        override fun newUploadFile(name: String): File = File(uploadDir, name)
        override suspend fun sendUpload(upload: DexUpload) {
            uploads.complete(upload)
        }
        override suspend fun attachment(messageId: String): DexFile? = if (messageId == "m1") DexFile(attachmentFile, "notes.txt", "text/plain") else null
        override fun sendTyping(peer: String, isTyping: Boolean) = Unit
        override suspend fun markRead(peer: String) = Unit
        override suspend fun react(messageId: String, emoji: String) = throw IllegalStateException("no such message")
        override suspend fun edit(messageId: String, body: String) = Unit
        override suspend fun delete(messageId: String, forEveryone: Boolean) = Unit
        override suspend fun retryAction(actionId: String) = Unit
        override suspend fun cancelAction(actionId: String) = Unit
        override suspend fun acceptOffer(messageId: String) = Unit
        override suspend fun declineOffer(messageId: String) = Unit
        override suspend fun cancelTransfer(messageId: String) = Unit
        override val callState: StateFlow<DexCallState> = MutableStateFlow(DexCallState())
        override val callEvents: Flow<DexCallEvent> = MutableSharedFlow()
        override fun onCallCommand(command: DexCallCommand) {
            if (command is DexCallCommand.Gone) gone.complete(command.clientId)
        }
    }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backend = FakeBackend()
    private val assets = object : DexAssets {
        override fun open(path: String): DexAsset? = if (path == "index.html") {
            val bytes = "<html>dex</html>".toByteArray()
            DexAsset("text/html; charset=utf-8", bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        } else {
            null
        }
    }
    private val turn = object : TurnAccess {
        override val port: Int = 8421
        override fun issue() = TurnCredential("u", "p")
    }
    private lateinit var server: DexServer
    private var port = 0

    @Before
    fun start() {
        server = DexServer(backend, assets, { ServerSocketFactory.getDefault() }, turn, scope)
        server.start(DexConfig(0, "harey", Passwords.hash("secret", iterations = 1_000), maxClients = 1))
        val running = runBlocking { withTimeout(5_000) { server.state.first { it is DexServerState.Running } } } as DexServerState.Running
        port = running.port
        assertTrue(port > 0)
    }

    @After
    fun stop() {
        server.stop()
        scope.cancel()
        backend.attachmentFile.delete()
        backend.uploadDir.deleteRecursively()
    }

    private fun connect(): Socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }

    private fun request(socket: Socket, head: String, body: ByteArray = ByteArray(0)): Reply {
        socket.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1) + body)
        socket.getOutputStream().flush()
        return readReply(socket.getInputStream())
    }

    private fun readReply(input: InputStream): Reply {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw EOFException("closed before a reply")
            head.append(b.toChar())
        }
        val lines = head.toString().split("\r\n").filter { it.isNotEmpty() }
        val status = lines[0].split(' ')[1].toInt()
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(body, offset, length - offset)
            if (n < 0) throw EOFException("body cut short")
            offset += n
        }
        return Reply(status, headers, body)
    }

    private fun login(socket: Socket, password: String = "secret"): Reply {
        val json = """{"username":"harey","password":"$password"}""".toByteArray()
        return request(socket, "POST /api/login HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\nContent-Length: ${json.size}\r\n\r\n", json)
    }

    private fun cookieOf(reply: Reply): String = reply.headers["set-cookie"]?.substringBefore(';') ?: fail("no cookie")

    private fun readServerFrame(input: InputStream): ServerFrame {
        val b0 = input.read()
        val b1 = input.read()
        if (b0 < 0 || b1 < 0) throw EOFException("socket closed")
        var length = b1 and 0x7F
        if (length == 126) length = (input.read() shl 8) or input.read()
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(payload, offset, length - offset)
            if (n < 0) throw EOFException("frame cut short")
            offset += n
        }
        if (b0 and 0x0F == WsOpcode.PING) return readServerFrame(input)
        assertEquals(WsOpcode.TEXT, b0 and 0x0F)
        return DexJson.decodeFromString(ServerFrame.serializer(), String(payload, Charsets.UTF_8))
    }

    private fun sendClientFrame(out: OutputStream, json: String) {
        out.write(WsCodec.encodeMasked(WsOpcode.TEXT, json.toByteArray(), byteArrayOf(1, 2, 3, 4)))
        out.flush()
    }

    private fun upgrade(socket: Socket, cookie: String?): Reply {
        val cookieLine = cookie?.let { "Cookie: $it\r\n" }.orEmpty()
        return request(socket, "GET /ws HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nUser-Agent: test\r\n${cookieLine}\r\n")
    }

    @Test
    fun `the bundle is served, api paths need a session, and the wrong password is refused`() {
        connect().use { socket ->
            val index = request(socket, "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
            assertEquals(200, index.status)
            assertEquals("<html>dex</html>", String(index.body))
            assertEquals("no-store", index.headers["cache-control"])
            assertEquals(404, request(socket, "GET /missing.js HTTP/1.1\r\nHost: x\r\n\r\n").status)
            assertEquals(401, request(socket, "GET /api/session HTTP/1.1\r\nHost: x\r\n\r\n").status)
            assertEquals(401, request(socket, "GET /a/m1 HTTP/1.1\r\nHost: x\r\n\r\n").status)
            assertEquals(401, upgrade(socket, null).status)
            val wrong = login(socket, password = "nope")
            assertEquals(401, wrong.status)
            assertTrue(String(wrong.body).contains("Wrong username"))
            assertEquals(405, request(socket, "GET /api/login HTTP/1.1\r\nHost: x\r\n\r\n").status)
        }
    }

    @Test
    fun `a login, a socket, a command, an attachment and a stop all work end to end`() {
        val socket = connect()
        val ok = login(socket)
        assertEquals(204, ok.status)
        val cookie = cookieOf(ok)
        assertTrue(cookie.startsWith("dex="))
        assertTrue(ok.headers.getValue("set-cookie").contains("HttpOnly"))

        val session = request(socket, "GET /api/session HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
        assertEquals(200, session.status)
        assertTrue(String(session.body).contains("\"ip\":\"10.42.0.1\""))

        val whole = request(socket, "GET /a/m1 HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n")
        assertEquals(200, whole.status)
        assertEquals("0123456789", String(whole.body))
        assertEquals("text/plain", whole.headers["content-type"])
        val part = request(socket, "GET /a/m1 HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nRange: bytes=2-4\r\n\r\n")
        assertEquals(206, part.status)
        assertEquals("234", String(part.body))
        assertEquals("bytes 2-4/10", part.headers["content-range"])
        assertEquals(416, request(socket, "GET /a/m1 HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nRange: bytes=10-\r\n\r\n").status)
        assertEquals(404, request(socket, "GET /a/other HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\n\r\n").status)

        val payload = "voice bytes".toByteArray()
        val uploaded = request(
            socket,
            "POST /a?peer=10.42.0.2&name=clip.m4a&mime=audio/mp4&voice=1&durationMs=1200 HTTP/1.1\r\nHost: x\r\nCookie: $cookie\r\nContent-Length: ${payload.size}\r\n\r\n",
            payload,
        )
        assertEquals(201, uploaded.status)
        val upload = backend.uploads.get(5, TimeUnit.SECONDS)
        assertEquals("10.42.0.2", upload.peer)
        assertTrue(upload.isVoice)
        assertEquals(1200L, upload.durationMs)
        assertEquals("voice bytes", upload.file.readText())

        val upgraded = upgrade(socket, cookie)
        assertEquals(101, upgraded.status)
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", upgraded.headers["sec-websocket-accept"])
        val input = socket.getInputStream()
        val hello = readServerFrame(input) as ServerFrame.Hello
        assertEquals("10.42.0.1", hello.me.ip)
        assertEquals(4_096L, hello.freeBytes)
        val frames = (1..6).map { readServerFrame(input) }
        val chats = frames.filterIsInstance<ServerFrame.Chats>().single()
        assertEquals("Bob", chats.items.single().label)
        assertTrue(frames.any { it is ServerFrame.CallState })
        assertEquals(1, server.clients.value.size)
        assertEquals("test", server.clients.value.single().userAgent)

        connect().use { second ->
            assertEquals(503, login(second).status)
        }

        sendClientFrame(socket.getOutputStream(), """{"t":"send_text","peer":"10.42.0.2","body":"hi"}""")
        assertEquals("10.42.0.2:hi", backend.sent.get(5, TimeUnit.SECONDS))

        sendClientFrame(socket.getOutputStream(), """{"t":"react","messageId":"m9","emoji":"👍"}""")
        val error = readServerFrame(input) as ServerFrame.Error
        assertEquals("no such message", error.message)
        assertEquals("m9", error.ref)

        sendClientFrame(socket.getOutputStream(), """{"t":"bogus"}""")
        assertEquals("Unknown frame", (readServerFrame(input) as ServerFrame.Error).message)

        sendClientFrame(socket.getOutputStream(), """{"t":"send_text","peer":"not an ip","body":"x"}""")
        assertEquals("Bad peer address", (readServerFrame(input) as ServerFrame.Error).message)

        sendClientFrame(socket.getOutputStream(), """{"t":"ping"}""")
        assertTrue(readServerFrame(input) is ServerFrame.Pong)

        assertTrue(server.send(hello.clientId, ServerFrame.Notice(com.telenebula.dex.wire.DexNoticeLevel.INFO, "hello there")))
        assertEquals("hello there", (readServerFrame(input) as ServerFrame.Notice).message)

        server.stop()
        assertEquals(DexServerState.Off, server.state.value)
        assertEquals(hello.clientId, backend.gone.get(5, TimeUnit.SECONDS))
        assertEquals(0, server.clients.value.size)
        try {
            while (input.read() >= 0) Unit
        } catch (e: SocketException) {
            // reset is as good as end of stream
        }
        socket.close()
    }

    @Test
    fun `a stopped server lets go of its port and a second start binds again`() {
        server.stop()
        server.start(DexConfig(port, "harey", Passwords.hash("secret", iterations = 1_000), maxClients = 2))
        val running = runBlocking { withTimeout(5_000) { server.state.first { it is DexServerState.Running } } } as DexServerState.Running
        assertEquals(port, running.port)
        connect().use { socket -> assertEquals(200, request(socket, "GET / HTTP/1.1\r\nHost: x\r\n\r\n").status) }
    }

    @Test
    fun `five wrong passwords lock the address`() {
        connect().use { socket ->
            repeat(Limits.LOGIN_FAILS_BEFORE_LOCK) { assertEquals(401, login(socket, "wrong").status) }
            val locked = login(socket)
            assertEquals(429, locked.status)
            assertNotNull(locked.headers["retry-after"])
        }
    }
}

private fun fail(message: String): Nothing = throw AssertionError(message)

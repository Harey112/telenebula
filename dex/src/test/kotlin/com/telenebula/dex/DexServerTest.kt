package com.telenebula.dex

import com.telenebula.dex.auth.Passwords
import com.telenebula.dex.http.WsCodec
import com.telenebula.dex.http.WsOpcode
import com.telenebula.dex.wire.DexAccount
import com.telenebula.dex.wire.DexCallLog
import com.telenebula.dex.wire.DexCallState
import com.telenebula.dex.wire.DexChat
import com.telenebula.dex.wire.DexChatLink
import com.telenebula.dex.wire.DexChatView
import com.telenebula.dex.wire.DexContact
import com.telenebula.dex.wire.DexContactDetail
import com.telenebula.dex.wire.DexContactFlags
import com.telenebula.dex.wire.DexContactNotifications
import com.telenebula.dex.wire.DexContactPrivacy
import com.telenebula.dex.wire.DexDiagnostics
import com.telenebula.dex.wire.DexIdentity
import com.telenebula.dex.wire.DexJson
import com.telenebula.dex.wire.DexMessage
import com.telenebula.dex.wire.DexNetwork
import com.telenebula.dex.wire.DexPingResult
import com.telenebula.dex.wire.DexPresence
import com.telenebula.dex.wire.DexQueue
import com.telenebula.dex.wire.DexSettings
import com.telenebula.dex.wire.DexSettingsPatch
import com.telenebula.dex.wire.DexStorage
import com.telenebula.dex.wire.DexThemeMode
import com.telenebula.dex.wire.DexUpdates
import com.telenebula.dex.wire.ServerFrame
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ServerSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

        val settingsFlow = MutableStateFlow(DexSettings())
        val patches = CompletableFuture<DexSettingsPatch>()
        val networkCollectors = AtomicInteger(0)
        val storageCollectors = AtomicInteger(0)
        val cleared = CompletableFuture<String>()

        override fun settings(): Flow<DexSettings> = settingsFlow
        override suspend fun applySettings(patch: DexSettingsPatch) {
            patches.complete(patch)
        }
        override suspend fun setQuickReaction(slot: Int, emoji: String) = Unit

        override fun account(): Flow<DexAccount> = flowOf(DexAccount(certName = "Me"))
        override fun network(): Flow<DexNetwork> = flow {
            networkCollectors.incrementAndGet()
            try {
                emit(DexNetwork(isTunnelOn = true))
                awaitCancellation()
            } finally {
                networkCollectors.decrementAndGet()
            }
        }
        override fun storage(): Flow<DexStorage> = flow {
            storageCollectors.incrementAndGet()
            try {
                emit(DexStorage(messages = 7))
                awaitCancellation()
            } finally {
                storageCollectors.decrementAndGet()
            }
        }
        override fun diagnostics(): Flow<DexDiagnostics> = flowOf(DexDiagnostics(logTail = "log"))
        override fun updates(): Flow<DexUpdates> = flowOf(DexUpdates(appVersion = "1.0.0"))

        override suspend fun contactDetail(peer: String): DexContactDetail? =
            if (peer == "10.42.0.2") DexContactDetail(DexContact(peer, "Bob", "Bob")) else null
        override suspend fun saveContact(peer: String, name: String, nickname: String, notes: String) = Unit
        override suspend fun addContact(peer: String, name: String, nickname: String, notes: String) = Unit
        override suspend fun deleteContact(peer: String) = Unit
        override suspend fun setContactFlags(peer: String, flags: DexContactFlags) = Unit
        override suspend fun setContactPrivacy(peer: String, privacy: DexContactPrivacy) = Unit
        override suspend fun setContactNotifications(peer: String, prefs: DexContactNotifications?) = Unit
        override suspend fun changeContactIp(peer: String, newIp: String) = Unit

        override suspend fun clearHistory(peer: String) {
            cleared.complete(peer)
        }
        override suspend fun clearAllHistory() = Unit
        override suspend fun clearOrphans(): Long = 512
        override suspend fun callLogs(peer: String?, limit: Int): List<DexCallLog> = emptyList()
        override suspend fun deleteCallLogs(ids: List<String>) = Unit
        override suspend fun chatMedia(peer: String): List<DexMessage> = emptyList()
        override suspend fun chatLinks(peer: String): List<DexChatLink> = listOf(DexChatLink("m1", "https://example.test", 1))
        override suspend fun search(peer: String, text: String): List<DexMessage> = emptyList()
        override suspend fun forward(messageId: String, peer: String) = Unit

        override suspend fun pingPeer(peer: String): DexPingResult = DexPingResult(peer, 12)
        override suspend fun retryFailed(peer: String) = Unit
        override suspend fun drain(peer: String) = Unit
        override suspend fun checkUpdates() = Unit
        override suspend fun setTunnel(isOn: Boolean) = throw IllegalStateException("The tunnel must be switched on from the phone the first time")
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
        // chats, contacts, presence, typing, queues, call state and settings all arrive unasked
        val frames = (1..7).map { readServerFrame(input) }
        val chats = frames.filterIsInstance<ServerFrame.Chats>().single()
        assertEquals("Bob", chats.items.single().label)
        assertTrue(frames.any { it is ServerFrame.CallState })
        assertTrue(frames.any { it is ServerFrame.Settings })
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
        // the socket is closed as stop returns, but the kernel's wind-down is its own business
        var isReleased = false
        for (attempt in 1..20) {
            isReleased = try {
                connect().close()
                false
            } catch (e: IOException) {
                true
            }
            if (isReleased) break
            Thread.sleep(100)
        }
        assertTrue("the old port still accepts two seconds after stop", isReleased)

        // port 0 again: re-binding the exact ephemeral port the OS just handed back is the
        // machine's decision, not the server's, and asserting it makes this test the machine's
        server.start(DexConfig(0, "harey", Passwords.hash("secret", iterations = 1_000), maxClients = 2))
        val running = runBlocking { withTimeout(5_000) { server.state.first { it is DexServerState.Running } } } as DexServerState.Running
        assertTrue(running.port > 0)
        port = running.port
        connect().use { socket -> assertEquals(200, request(socket, "GET / HTTP/1.1\r\nHost: x\r\n\r\n").status) }
    }

    /** Logs in, upgrades and drains the frames every client is sent unasked. */
    private fun openSocket(): Pair<Socket, InputStream> {
        val socket = connect()
        val cookie = login(socket).headers["set-cookie"]?.substringBefore(';')
        assertEquals(101, upgrade(socket, cookie).status)
        val input = socket.getInputStream()
        repeat(8) { readServerFrame(input) }
        return socket to input
    }

    @Test
    fun `a watched section is collected only while it is watched`() {
        val (socket, input) = openSocket()
        socket.use {
            assertEquals(0, backend.networkCollectors.get())

            sendClientFrame(socket.getOutputStream(), """{"t":"watch","sections":["network","storage"]}""")
            val first = (1..3).map { readServerFrame(input) }
            assertTrue(first.any { f -> f is ServerFrame.Network && f.network.isTunnelOn })
            assertTrue(first.any { f -> f is ServerFrame.Storage && f.storage.messages == 7 })
            assertTrue(first.any { it is ServerFrame.Done })
            assertEquals(1, backend.networkCollectors.get())
            assertEquals(1, backend.storageCollectors.get())

            // narrowing the list stops what is no longer being looked at
            sendClientFrame(socket.getOutputStream(), """{"t":"watch","sections":["storage"]}""")
            readServerFrame(input)
            waitFor { backend.networkCollectors.get() == 0 }
            assertEquals(1, backend.storageCollectors.get())

            sendClientFrame(socket.getOutputStream(), """{"t":"watch","sections":[]}""")
            readServerFrame(input)
            waitFor { backend.storageCollectors.get() == 0 }
        }
    }

    @Test
    fun `a settings patch reaches the phone carrying only what changed`() {
        val (socket, input) = openSocket()
        socket.use {
            sendClientFrame(socket.getOutputStream(), """{"t":"set_settings","patch":{"themeMode":"dark"}}""")
            val done = readServerFrame(input) as ServerFrame.Done
            assertEquals("set_settings", done.what)
            val patch = backend.patches.get(5, TimeUnit.SECONDS)
            assertEquals(DexThemeMode.DARK, patch.themeMode)
            assertEquals(null, patch.colorTheme)
            assertEquals(null, patch.sendReadReceipts)
        }
    }

    @Test
    fun `a command answers done and a one-shot request answers its own frame`() {
        val (socket, input) = openSocket()
        socket.use {
            sendClientFrame(socket.getOutputStream(), """{"t":"clear_history","peer":"10.42.0.2"}""")
            assertEquals("clear_history", (readServerFrame(input) as ServerFrame.Done).what)
            assertEquals("10.42.0.2", backend.cleared.get(5, TimeUnit.SECONDS))

            sendClientFrame(socket.getOutputStream(), """{"t":"request_chat_links","peer":"10.42.0.2"}""")
            val links = readServerFrame(input) as ServerFrame.ChatLinks
            assertEquals("https://example.test", links.items.single().url)

            sendClientFrame(socket.getOutputStream(), """{"t":"ping_peer","peer":"10.42.0.2"}""")
            assertEquals(12L, (readServerFrame(input) as ServerFrame.PingResult).result.rttMs)
        }
    }

    @Test
    fun `an unknown peer and a refused command are answered, not crashed`() {
        val (socket, input) = openSocket()
        socket.use {
            sendClientFrame(socket.getOutputStream(), """{"t":"request_contact_detail","peer":"10.42.9.9"}""")
            val missing = readServerFrame(input) as ServerFrame.Error
            assertEquals("request_contact_detail", missing.ref)

            sendClientFrame(socket.getOutputStream(), """{"t":"request_contact_detail","peer":"nonsense!!"}""")
            assertEquals("Bad peer address", (readServerFrame(input) as ServerFrame.Error).message)

            // the tunnel needs the system's consent, which only the phone can give
            sendClientFrame(socket.getOutputStream(), """{"t":"set_tunnel","isOn":true}""")
            val refused = readServerFrame(input) as ServerFrame.Error
            assertEquals("set_tunnel", refused.ref)
            assertTrue(refused.message.contains("from the phone"))

            // the socket still works after all of that
            sendClientFrame(socket.getOutputStream(), """{"t":"ping"}""")
            assertTrue(readServerFrame(input) is ServerFrame.Pong)
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("the condition never held", condition())
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

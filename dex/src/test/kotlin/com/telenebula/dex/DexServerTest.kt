package com.telenebula.dex

import com.telenebula.dex.auth.Passwords
import com.telenebula.dex.http.LanAdmission
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
import kotlinx.coroutines.launch
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
        val pngFile = File.createTempFile("dex", ".png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24)) }
        val htmlFile = File.createTempFile("dex", ".html").apply { writeText("<!doctype html><script>fetch('/api/session')</script>") }
        @Volatile var free = 1L shl 40
        @Volatile var attachmentFailure: Exception? = null
        val uploadDir = File(System.getProperty("java.io.tmpdir"), "dex-test-${System.nanoTime()}")

        override fun chats(): Flow<List<DexChat>> = chatList
        override fun contacts(): Flow<List<DexContact>> = flowOf(emptyList())
        override fun chat(peer: String): Flow<DexChatView> = flowOf(DexChatView(peer, DexContact(peer, "Bob", "Bob"), emptyList(), hasMore = false))
        override suspend fun messagesBefore(peer: String, beforeTs: Long, beforeId: String, limit: Int): List<DexMessage> = emptyList()
        override fun presence(): Flow<Map<String, DexPresence>> = flowOf(emptyMap())
        override fun typing(): Flow<Set<String>> = flowOf(emptySet())
        override fun queues(): Flow<List<DexQueue>> = flowOf(emptyList())
        override fun tunnel(): Flow<Boolean> = flowOf(true)
        override suspend fun freeBytes(): Long = free
        override suspend fun sendText(peer: String, body: String, replyTo: String?, isCovered: Boolean) {
            sent.complete("$peer:$body")
        }
        override suspend fun checkUpload(peer: String) {
            if (peer == BLOCKED) throw DexException(DexFailure.REFUSED, "You blocked this contact. Unblock them to send.")
        }
        override fun newUploadFile(name: String): File = File(uploadDir, name)
        override suspend fun sendUpload(upload: DexUpload) {
            uploads.complete(upload)
        }
        override suspend fun attachment(messageId: String): DexFile? {
            attachmentFailure?.let { throw it }
            return when (messageId) {
                "m1" -> DexFile(attachmentFile, "notes.txt", "text/plain")
                "png" -> DexFile(pngFile, "photo.png", "image/png")
                "html" -> DexFile(htmlFile, "page.html", "text/html")
                "fake-png" -> DexFile(htmlFile, "photo.png", "image/png")
                "crlf" -> DexFile(attachmentFile, "x\r\nSet-Cookie: stolen=1.txt", "text/plain\r\nSet-Cookie: stolen=1")
                else -> null
            }
        }
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
        override val callState = MutableStateFlow(DexCallState())
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
        override suspend fun saveContact(peer: String, nickname: String, notes: String) = Unit
        override suspend fun addContact(peer: String, nickname: String, notes: String) = Unit
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
    private class FakeTurn : TurnAccess {
        override val port: Int = 8421
        val revoked = java.util.concurrent.CopyOnWriteArrayList<String>()
        val revokedAll = AtomicInteger(0)
        override fun issue(owner: String, peer: java.net.InetAddress) = TurnCredential("u", "p")
        override fun revoke(owner: String) {
            revoked.add(owner)
        }
        override fun revokeAll() {
            revokedAll.incrementAndGet()
        }
    }
    private val turn = FakeTurn()
    private val loopbackOnly = LanAdmission({ listOf(java.net.InetAddress.getByName("127.0.0.1")) })
    private lateinit var server: DexServer
    private var port = 0

    @Before
    fun start() {
        server = DexServer(backend, assets, { ServerSocketFactory.getDefault() }, turn, scope, loopbackOnly)
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
        backend.pngFile.delete()
        backend.htmlFile.delete()
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

    private val host: String get() = "Host: 127.0.0.1:$port\r\n"
    private val origin: String get() = "Origin: https://127.0.0.1:$port\r\n"

    private fun login(socket: Socket, password: String = "secret", originLine: String = origin): Reply {
        val json = """{"username":"harey","password":"$password"}""".toByteArray()
        return request(socket, "POST /api/login HTTP/1.1\r\n$host${originLine}Content-Type: application/json\r\nContent-Length: ${json.size}\r\n\r\n", json)
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

    private fun upgrade(socket: Socket, cookie: String?, originLine: String = origin, hostLine: String = host): Reply {
        val cookieLine = cookie?.let { "Cookie: $it\r\n" }.orEmpty()
        return request(socket, "GET /ws HTTP/1.1\r\n${hostLine}Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nUser-Agent: test\r\n${originLine}${cookieLine}\r\n")
    }

    @Test
    fun `the bundle is served, api paths need a session, and the wrong password is refused`() {
        connect().use { socket ->
            val index = request(socket, "GET / HTTP/1.1\r\n$host\r\n")
            assertEquals(200, index.status)
            assertEquals("<html>dex</html>", String(index.body))
            assertEquals("no-store", index.headers["cache-control"])
            assertEquals(404, request(socket, "GET /missing.js HTTP/1.1\r\n$host\r\n").status)
            assertEquals(401, request(socket, "GET /api/session HTTP/1.1\r\n$host\r\n").status)
            assertEquals(401, request(socket, "GET /a/m1 HTTP/1.1\r\n$host\r\n").status)
            assertEquals(401, upgrade(socket, null).status)
            val wrong = login(socket, password = "nope")
            assertEquals(401, wrong.status)
            assertTrue(String(wrong.body).contains("Wrong username"))
            assertEquals(405, request(socket, "GET /api/login HTTP/1.1\r\n$host\r\n").status)
        }
    }

    @Test
    fun `a login, a socket, a command, an attachment and a stop all work end to end`() {
        val socket = connect()
        val ok = login(socket)
        assertEquals(204, ok.status)
        val cookie = cookieOf(ok)
        assertTrue(cookie.startsWith("__Host-dex="))
        assertTrue(ok.headers.getValue("set-cookie").contains("HttpOnly"))

        val session = request(socket, "GET /api/session HTTP/1.1\r\n${host}Cookie: $cookie\r\n\r\n")
        assertEquals(200, session.status)
        assertTrue(String(session.body).contains("\"ip\":\"10.42.0.1\""))

        val whole = request(socket, "GET /a/m1 HTTP/1.1\r\n${host}Cookie: $cookie\r\n\r\n")
        assertEquals(200, whole.status)
        assertEquals("0123456789", String(whole.body))
        // a text file is never rendered on the Dex origin, whatever its sender called it
        assertEquals("application/octet-stream", whole.headers["content-type"])
        assertTrue(whole.headers.getValue("content-disposition").startsWith("attachment;"))
        assertEquals("private, no-store", whole.headers["cache-control"])
        val part = request(socket, "GET /a/m1 HTTP/1.1\r\n${host}Cookie: $cookie\r\nRange: bytes=2-4\r\n\r\n")
        assertEquals(206, part.status)
        assertEquals("234", String(part.body))
        assertEquals("bytes 2-4/10", part.headers["content-range"])
        assertEquals(416, request(socket, "GET /a/m1 HTTP/1.1\r\n${host}Cookie: $cookie\r\nRange: bytes=10-\r\n\r\n").status)
        assertEquals(404, request(socket, "GET /a/other HTTP/1.1\r\n${host}Cookie: $cookie\r\n\r\n").status)

        val payload = "voice bytes".toByteArray()
        val uploaded = request(
            socket,
            "POST /a?peer=10.42.0.2&name=clip.m4a&mime=audio/mp4&voice=1&durationMs=1200 HTTP/1.1\r\n$host${origin}Cookie: $cookie\r\nContent-Length: ${payload.size}\r\n\r\n",
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
        assertEquals(1L shl 40, hello.freeBytes)
        // chats, contacts, presence, typing, queues, the tunnel, call state and settings all arrive unasked
        val frames = (1..8).map { readServerFrame(input) }
        val chats = frames.filterIsInstance<ServerFrame.Chats>().single()
        assertEquals("Bob", chats.items.single().label)
        assertTrue(frames.any { it is ServerFrame.CallState })
        assertTrue(frames.any { it is ServerFrame.Settings })
        assertEquals(true, frames.filterIsInstance<ServerFrame.Tunnel>().single().isOn)
        assertEquals(1, server.clients.value.size)
        assertEquals("test", server.clients.value.single().userAgent)

        connect().use { second ->
            assertEquals(503, login(second).status)
        }

        sendClientFrame(socket.getOutputStream(), """{"t":"send_text","peer":"10.42.0.2","body":"hi","requestId":"r1"}""")
        assertEquals("10.42.0.2:hi", backend.sent.get(5, TimeUnit.SECONDS))
        val sent = readServerFrame(input) as ServerFrame.Done
        assertEquals("send_text", sent.what)
        assertEquals("r1", sent.requestId)

        sendClientFrame(socket.getOutputStream(), """{"t":"send_text","peer":"10.42.0.2","body":"legacy"}""")
        assertEquals(null, (readServerFrame(input) as ServerFrame.Done).requestId)

        sendClientFrame(socket.getOutputStream(), """{"t":"edit","messageId":"m1","body":"corrected"}""")
        val edited = readServerFrame(input) as ServerFrame.Done
        assertEquals("edit", edited.what)
        assertEquals("m1", edited.requestId)

        sendClientFrame(socket.getOutputStream(), """{"t":"react","messageId":"m9","emoji":"👍"}""")
        val error = readServerFrame(input) as ServerFrame.Error
        assertEquals("no such message", error.message)
        assertEquals("m9", error.ref)

        sendClientFrame(socket.getOutputStream(), """{"t":"bogus"}""")
        assertEquals("Unknown frame", (readServerFrame(input) as ServerFrame.Error).message)

        sendClientFrame(socket.getOutputStream(), """{"t":"send_text","peer":"not an ip","body":"x","requestId":"r2"}""")
        val refusedText = readServerFrame(input) as ServerFrame.Error
        assertEquals("Bad peer address", refusedText.message)
        assertEquals("r2", refusedText.requestId)

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
        connect().use { socket -> assertEquals(200, request(socket, "GET / HTTP/1.1\r\n$host\r\n").status) }
    }

    /** Logs in, upgrades and drains the frames every client is sent unasked. */
    private fun openSocket(): Pair<Socket, InputStream> {
        val socket = connect()
        val cookie = login(socket).headers["set-cookie"]?.substringBefore(';')
        assertEquals(101, upgrade(socket, cookie).status)
        val input = socket.getInputStream()
        repeat(9) { readServerFrame(input) }
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
            sendClientFrame(socket.getOutputStream(), """{"t":"set_settings","patch":{"dexProfile":{"themeMode":"dark"}}}""")
            val done = readServerFrame(input) as ServerFrame.Done
            assertEquals("set_settings", done.what)
            val patch = backend.patches.get(5, TimeUnit.SECONDS)
            assertEquals(DexThemeMode.DARK, patch.dexProfile?.themeMode)
            assertEquals(null, patch.presence)
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

    /** Reads until the server closes the socket; the close code when a close frame came first. */
    private fun awaitClose(input: InputStream): Int? {
        try {
            while (true) {
                val b0 = input.read()
                if (b0 < 0) return null
                var length = input.read() and 0x7F
                if (length == 126) length = (input.read() shl 8) or input.read()
                val payload = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val n = input.read(payload, offset, length - offset)
                    if (n < 0) return null
                    offset += n
                }
                if (b0 and 0x0F == WsOpcode.CLOSE) return if (length >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else null
            }
        } catch (e: SocketException) {
            return null
        }
    }

    private fun loggedInSocket(): Triple<Socket, InputStream, String> {
        val socket = connect()
        val cookie = cookieOf(login(socket))
        assertEquals(101, upgrade(socket, cookie).status)
        val input = socket.getInputStream()
        repeat(9) { readServerFrame(input) }
        return Triple(socket, input, cookie)
    }

    @Test
    fun `logging out closes the open socket and the cookie opens nothing after`() {
        val (socket, input, cookie) = loggedInSocket()
        socket.use {
            connect().use { other ->
                val out = request(other, "POST /api/logout HTTP/1.1\r\n$host${origin}Cookie: $cookie\r\nContent-Length: 0\r\n\r\n")
                assertEquals(204, out.status)
                assertTrue(out.headers.getValue("set-cookie").contains("Max-Age=0"))
            }
            assertEquals(1008, awaitClose(input))
        }
        connect().use { again ->
            assertEquals(401, upgrade(again, cookie).status)
            assertEquals(401, request(again, "GET /a/m1 HTTP/1.1\r\n${host}Cookie: $cookie\r\n\r\n").status)
        }
    }

    @Test
    fun `new credentials close every socket and void every cookie`() {
        val (socket, input, cookie) = loggedInSocket()
        socket.use {
            server.update(DexConfig(0, "harey", Passwords.hash("rotated", iterations = 1_000), maxClients = 1))
            assertEquals(1008, awaitClose(input))
        }
        connect().use { again ->
            assertEquals(401, upgrade(again, cookie).status)
            assertEquals(401, login(again).status)
            assertEquals(204, login(again, password = "rotated").status)
        }
    }

    @Test
    fun `the phone signing a browser out closes its socket and its login`() {
        val (socket, input, cookie) = loggedInSocket()
        socket.use {
            val id = server.clients.value.single().id
            assertTrue(server.disconnect(id))
            assertEquals(1008, awaitClose(input))
            waitFor { turn.revoked.contains(id) }
        }
        assertEquals(false, server.disconnect("nobody"))
        connect().use { again -> assertEquals(401, upgrade(again, cookie).status) }
    }

    @Test
    fun `an upgrade from any origin but the page's own, or naming another host, is refused`() {
        val socket = connect()
        val cookie = cookieOf(login(socket))
        socket.close()
        for (originLine in listOf("", "Origin: null\r\n", "Origin: http://127.0.0.1:$port\r\n", "Origin: https://127.0.0.1\r\n", "Origin: https://127.0.0.1:${port + 1}\r\n", "Origin: https://evil.example:$port\r\n")) {
            connect().use { assertEquals("origin '$originLine'", 403, upgrade(it, cookie, originLine = originLine).status) }
        }
        for (hostLine in listOf("", "Host: 127.0.0.1\r\n", "Host: 127.0.0.1:${port + 1}\r\n", "Host: 127.0.0.1:$port\r\nHost: evil.example:$port\r\n", "Host: exa mple\r\n")) {
            connect().use { assertEquals("host '$hostLine'", 400, upgrade(it, cookie, hostLine = hostLine).status) }
        }
        assertEquals(0, server.clients.value.size)
    }

    @Test
    fun `a login or an upload that does not come from the page is refused`() {
        // a refused POST leaves its body unread, so each is answered and its connection closed
        for (originLine in listOf("", "Origin: https://evil.example\r\n", "Sec-Fetch-Site: cross-site\r\n", "Origin: https://127.0.0.1:$port\r\nSec-Fetch-Site: same-site\r\n")) {
            connect().use { assertEquals("'$originLine'", 403, login(it, originLine = originLine).status) }
        }
        connect().use { assertEquals(204, login(it, originLine = "Sec-Fetch-Site: same-origin\r\n").status) }
        connect().use { socket ->
            val cookie = cookieOf(login(socket))
            val cross = request(socket, "POST /a?peer=10.42.0.2&name=a.bin HTTP/1.1\r\n${host}Origin: https://evil.example\r\nCookie: $cookie\r\nContent-Length: 1\r\n\r\n", byteArrayOf(1))
            assertEquals(403, cross.status)
        }
        assertEquals(false, backend.uploads.isDone)
    }

    @Test
    fun `attachments are inline only as passive media whose bytes agree`() {
        connect().use { socket ->
            val cookie = cookieOf(login(socket))
            fun get(id: String, extra: String = "") = request(socket, "GET /a/$id HTTP/1.1\r\n${host}Cookie: $cookie\r\n$extra\r\n")

            val png = get("png")
            assertEquals("image/png", png.headers["content-type"])
            assertTrue(png.headers.getValue("content-disposition").startsWith("inline;"))
            assertEquals("private, no-store", png.headers["cache-control"])
            assertTrue(png.headers.getValue("content-security-policy").contains("sandbox"))
            assertEquals("nosniff", png.headers["x-content-type-options"])
            val range = get("png", "Range: bytes=0-3\r\n")
            assertEquals(206, range.status)
            assertEquals(4, range.body.size)
            assertEquals("image/png", range.headers["content-type"])

            for (id in listOf("html", "fake-png")) {
                val reply = get(id)
                assertEquals(id, "application/octet-stream", reply.headers["content-type"])
                assertTrue(id, reply.headers.getValue("content-disposition").startsWith("attachment;"))
                assertTrue(id, reply.headers.getValue("content-security-policy").contains("sandbox"))
            }

            val crlf = get("crlf")
            assertEquals(200, crlf.status)
            assertEquals(null, crlf.headers["set-cookie"])
            assertEquals("application/octet-stream", crlf.headers["content-type"])
            assertTrue(crlf.headers.getValue("content-disposition").contains("%0D%0ASet-Cookie"))

            // an image another site embeds does not get the cookie's answer
            assertEquals(403, get("png", "Sec-Fetch-Site: cross-site\r\n").status)
        }
    }

    @Test
    fun `the page names its own socket in its policy`() {
        connect().use { socket ->
            val csp = request(socket, "GET / HTTP/1.1\r\n$host\r\n").headers.getValue("content-security-policy")
            assertTrue(csp.contains("connect-src 'self' wss://127.0.0.1:$port;"))
            assertTrue(csp.contains("object-src 'none'"))
            assertTrue(csp.contains("frame-ancestors 'none'"))
        }
    }

    @Test
    fun `an upload the phone refuses, or that would not fit, stages nothing`() {
        connect().use { socket ->
            val cookie = cookieOf(login(socket))
            fun upload(peer: String) = request(socket, "POST /a?peer=$peer&name=a.bin HTTP/1.1\r\n$host${origin}Cookie: $cookie\r\nContent-Length: 4\r\n\r\n", byteArrayOf(1, 2, 3, 4))
            val refused = upload(BLOCKED)
            assertEquals(409, refused.status)
            assertTrue(String(refused.body).contains("blocked"))
        }
        connect().use { socket ->
            val cookie = cookieOf(login(socket))
            backend.free = Limits.UPLOAD_FREE_SPACE_RESERVE + 2
            val full = request(socket, "POST /a?peer=10.42.0.2&name=a.bin HTTP/1.1\r\n$host${origin}Cookie: $cookie\r\nContent-Length: 4\r\n\r\n", byteArrayOf(1, 2, 3, 4))
            assertEquals(507, full.status)
        }
        assertEquals(false, backend.uploads.isDone)
        assertTrue(backend.uploadDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `the last client place is taken before the upgrade is answered`() {
        val (socket, _, cookie) = loggedInSocket()
        socket.use {
            connect().use { second -> assertEquals(503, upgrade(second, cookie).status) }
            assertEquals(1, server.clients.value.size)
        }
        waitFor { server.clients.value.isEmpty() }
        connect().use { third ->
            val fresh = cookieOf(login(third))
            assertEquals(101, upgrade(third, fresh).status)
        }
    }

    @Test
    fun `an internal failure tells the browser nothing and the phone everything`() {
        val fault = CompletableFuture<String>()
        val watcher = scope.launch { server.faults.collect { fault.complete(it) } }
        try {
            connect().use { socket ->
                val cookie = cookieOf(login(socket))
                backend.attachmentFailure = IllegalStateException("/data/user/0/secret/path")
                val reply = request(socket, "GET /a/m1 HTTP/1.1\r\n${host}Cookie: $cookie\r\n\r\n")
                assertEquals(500, reply.status)
                assertTrue(!String(reply.body).contains("secret"))
                assertTrue(fault.get(5, TimeUnit.SECONDS).contains("/data/user/0/secret/path"))
            }
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun `settings only a phone owns are not in the wire, so a crafted frame changes nothing`() {
        val (socket, input) = openSocket()
        socket.use {
            sendClientFrame(
                socket.getOutputStream(),
                """{"t":"set_settings","patch":{"isDeveloperMode":true,"isScreenshotBlocked":false,"isStartOnBootEnabled":false,"isBackgroundConnectionEnabled":false,"appLockAfterSec":0,"notifications":{},"autoCleanOrphans":true,"quickReactions":["x"]}}""",
            )
            assertEquals("set_settings", (readServerFrame(input) as ServerFrame.Done).what)
            assertEquals(DexSettingsPatch(), backend.patches.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a connection that reached an address the phone does not offer is closed unanswered and reported`() {
        server.stop()
        val fault = CompletableFuture<String>()
        val closed = DexServer(backend, assets, { ServerSocketFactory.getDefault() }, turn, scope, LanAdmission({ emptyList() }))
        closed.start(DexConfig(0, "harey", Passwords.hash("secret", iterations = 1_000), maxClients = 1))
        val running = runBlocking { withTimeout(5_000) { closed.state.first { it is DexServerState.Running } } } as DexServerState.Running
        val watcher = scope.launch { closed.faults.collect { fault.complete(it) } }
        try {
            Socket("127.0.0.1", running.port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: 127.0.0.1:${running.port}\r\n\r\n".toByteArray())
                val first = try {
                    socket.getInputStream().read()
                } catch (e: SocketException) {
                    -1
                }
                assertEquals(-1, first)
            }
            assertTrue(fault.get(5, TimeUnit.SECONDS).contains("127.0.0.1"))
        } finally {
            watcher.cancel()
            closed.stop()
        }
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

private const val BLOCKED = "10.42.0.3"

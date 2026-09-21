package com.telenebula.dex

import com.telenebula.dex.auth.LoginThrottle
import com.telenebula.dex.auth.Passwords
import com.telenebula.dex.auth.Sessions
import com.telenebula.dex.http.BodyReader
import com.telenebula.dex.http.Cidr
import com.telenebula.dex.http.ClientSession
import com.telenebula.dex.http.HttpBody
import com.telenebula.dex.http.HttpError
import com.telenebula.dex.http.HttpParser
import com.telenebula.dex.http.HttpRequest
import com.telenebula.dex.http.HttpResponse
import com.telenebula.dex.http.HttpWriter
import com.telenebula.dex.http.RangeResult
import com.telenebula.dex.http.Ranges
import com.telenebula.dex.http.WsHandshake
import com.telenebula.dex.wire.DexIceServer
import com.telenebula.dex.wire.DexJson
import com.telenebula.dex.wire.ServerFrame
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.net.ServerSocketFactory
import javax.net.ssl.SSLServerSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The phone's web server: HTTPS for the bundle, the login and attachments, WebSocket for
 * everything live. One run per start; stop cancels every connection and closes every socket.
 */
class DexServer(
    private val backend: DexBackend,
    private val assets: DexAssets,
    private val socketFactory: () -> ServerSocketFactory,
    private val turn: TurnAccess,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private class Run(@Volatile var config: DexConfig, val job: Job) {
        @Volatile var serverSocket: ServerSocket? = null
        val connections = HashSet<Socket>()

        fun register(socket: Socket): Boolean = synchronized(connections) {
            if (connections.size >= Limits.MAX_CONNECTIONS) return false
            connections.add(socket)
        }

        fun unregister(socket: Socket) = synchronized(connections) { connections.remove(socket) }

        fun closeAll() {
            val open = synchronized(connections) { connections.toList().also { connections.clear() } }
            for (socket in open) closeQuietly(socket)
            serverSocket?.let { closeQuietly(it) }
        }
    }

    private sealed interface Outcome {
        class Response(val response: HttpResponse) : Outcome
        class Upgrade(val acceptKey: String, val username: String) : Outcome
    }

    private val lock = Any()
    private var run: Run? = null
    private val sessions = Sessions(now)
    private val throttle = LoginThrottle(now)
    private val clientSessions = ConcurrentHashMap<String, ClientSession>()
    private val random = SecureRandom()

    private val mutableState = MutableStateFlow<DexServerState>(DexServerState.Off)
    val state: StateFlow<DexServerState> = mutableState.asStateFlow()

    private val mutableClients = MutableStateFlow<List<DexClient>>(emptyList())
    val clients: StateFlow<List<DexClient>> = mutableClients.asStateFlow()

    private val faultFlow = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** failures with nobody to answer: a connection handler that blew up, a bind that was lost */
    val faults: SharedFlow<String> = faultFlow.asSharedFlow()

    /** Starts, or re-applies [config] to a running server; a port change restarts it. Idempotent. */
    fun start(config: DexConfig) {
        val cleaned = DexConfig(config.port, config.username, config.password, config.maxClients.coerceIn(1, Limits.MAX_CLIENTS))
        synchronized(lock) {
            val current = run
            if (current != null) {
                if (current.config.port == cleaned.port) {
                    current.config = cleaned
                    return
                }
                stopLocked(current)
            }
            run = launchRun(cleaned)
        }
    }

    fun update(config: DexConfig) = start(config)

    fun stop() {
        synchronized(lock) {
            run?.let { stopLocked(it) }
            run = null
        }
        sessions.clear()
        mutableState.value = DexServerState.Off
    }

    fun send(clientId: String, frame: ServerFrame): Boolean = clientSessions[clientId]?.send(frame) ?: false

    fun broadcast(frame: ServerFrame) {
        for (session in clientSessions.values) session.send(frame)
    }

    private fun stopLocked(current: Run) {
        current.job.cancel()
        current.closeAll()
    }

    private fun launchRun(config: DexConfig): Run {
        val job = SupervisorJob(scope.coroutineContext[Job])
        val run = Run(config, job)
        val handler = CoroutineExceptionHandler { _, e -> if (e !is CancellationException) faultFlow.tryEmit("Dex: ${e.message ?: e.javaClass.simpleName}") }
        val runScope = CoroutineScope(scope.coroutineContext + job + io + handler)
        runScope.launch { serve(run) }
        return run
    }

    /** Only the current run may publish: a cancelled one must never overwrite its successor's state. */
    private fun publish(run: Run, state: DexServerState, isFinished: Boolean = false) {
        synchronized(lock) {
            if (this.run !== run) return
            if (isFinished) this.run = null
            mutableState.value = state
        }
    }

    private suspend fun CoroutineScope.serve(run: Run) {
        publish(run, DexServerState.Starting)
        val server = try {
            withContext(io) { listenOn(run.config.port) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            publish(run, DexServerState.Failed("Port ${run.config.port} could not be opened: ${e.message ?: e.javaClass.simpleName}"), isFinished = true)
            return
        }
        run.serverSocket = server
        if (!isActive) {
            closeQuietly(server)
            return
        }
        publish(run, DexServerState.Running(server.localPort))
        try {
            while (isActive) {
                val socket = try {
                    withContext(io) { server.accept() }
                } catch (e: IOException) {
                    if (server.isClosed || !isActive) break
                    continue
                }
                if (!run.register(socket)) {
                    launch { refuse(socket) }
                    continue
                }
                launch { handleConnection(run, socket) }
            }
        } finally {
            closeQuietly(server)
            run.closeAll()
            publish(run, DexServerState.Off, isFinished = true)
        }
    }

    /** A restart races the previous socket's wind-down, so a refused port is retried before it is reported. */
    private suspend fun listenOn(port: Int): ServerSocket {
        var attempt = 1
        while (true) {
            try {
                return socketFactory().createServerSocket().apply {
                    reuseAddress = true
                    if (this is SSLServerSocket) enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                    bind(InetSocketAddress(port), BACKLOG)
                }
            } catch (e: IOException) {
                if (attempt >= BIND_ATTEMPTS) throw e
                attempt += 1
                delay(BIND_RETRY_MS)
            }
        }
    }

    private suspend fun refuse(socket: Socket) {
        try {
            withContext(io) {
                socket.soTimeout = Limits.READ_TIMEOUT_MS
                HttpWriter.write(socket.getOutputStream(), HttpResponse.error(503, "Too many connections"), isHead = false, isKeepAlive = false)
            }
        } catch (e: IOException) {
            // the client is gone; nothing to answer
        } finally {
            closeQuietly(socket)
        }
    }

    // --- one connection -----------------------------------------------------------------------

    private suspend fun handleConnection(run: Run, socket: Socket) {
        try {
            val remote = socket.inetAddress
            if (remote == null || isOverlay(remote)) return
            withContext(io) {
                socket.soTimeout = Limits.READ_TIMEOUT_MS
                socket.tcpNoDelay = true
            }
            val input = BufferedInputStream(socket.getInputStream(), INPUT_BUFFER)
            val output = BufferedOutputStream(socket.getOutputStream(), OUTPUT_BUFFER)
            while (true) {
                val request = try {
                    withContext(io) { HttpParser.read(input) } ?: return
                } catch (e: HttpError) {
                    withContext(io) { HttpWriter.write(output, HttpResponse.error(e.status, e.message ?: HttpResponse.reason(e.status)), isHead = false, isKeepAlive = false) }
                    return
                }
                val body = BodyReader(input, request.contentLength)
                when (val outcome = route(run, socket, request, body)) {
                    is Outcome.Response -> {
                        val isKeepAlive = request.isKeepAlive && outcome.response.isKeepAliveAllowed && body.isDrained
                        withContext(io) {
                            HttpWriter.write(output, outcome.response, isHead = request.method == "HEAD", isKeepAlive = isKeepAlive)
                            if (isKeepAlive) socket.soTimeout = Limits.IDLE_TIMEOUT_MS
                        }
                        if (!isKeepAlive) return
                    }
                    is Outcome.Upgrade -> {
                        withContext(io) {
                            output.write(upgradeHead(outcome.acceptKey))
                            output.flush()
                            socket.soTimeout = (Limits.WS_PING_INTERVAL_MS * 3).toInt()
                        }
                        serveClient(socket, input, output, request)
                        return
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            // idle or stalled: closed below
        } catch (e: IOException) {
            // the browser went away mid-exchange: closed below
        } catch (e: Exception) {
            faultFlow.tryEmit("Dex connection failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            run.unregister(socket)
            closeQuietly(socket)
        }
    }

    private suspend fun serveClient(socket: Socket, input: InputStream, output: OutputStream, request: HttpRequest) {
        val client = DexClient(
            id = newClientId(),
            remoteAddress = socket.inetAddress?.hostAddress.orEmpty(),
            userAgent = request.header("user-agent").orEmpty().take(MAX_USER_AGENT_CHARS),
            connectedAt = now(),
        )
        val local = socket.localAddress
        val session = ClientSession(client, socket, input, output, backend, iceServers = { iceServersFor(local) }, io = io)
        clientSessions[client.id] = session
        publishClients()
        try {
            session.run()
        } finally {
            clientSessions.remove(client.id)
            publishClients()
            try {
                backend.onCallCommand(DexCallCommand.Gone(client.id))
            } catch (e: Exception) {
                faultFlow.tryEmit("Dex: ${backend.describe(e)}")
            }
        }
    }

    private fun publishClients() {
        mutableClients.value = clientSessions.values.map { it.client }.sortedBy { it.connectedAt }
    }

    private fun iceServersFor(local: InetAddress?): List<DexIceServer> {
        val host = local?.let { hostForUrl(it) } ?: return emptyList()
        val credential = turn.issue()
        return listOf(DexIceServer(listOf("turn:$host:${turn.port}?transport=udp"), credential.username, credential.password))
    }

    private fun hostForUrl(address: InetAddress): String {
        val text = address.hostAddress.orEmpty().substringBefore('%')
        return if (address is Inet6Address) "[$text]" else text
    }

    private fun isOverlay(address: InetAddress): Boolean {
        val networks = backend.overlayNetworks.value.mapNotNull { Cidr.parse(it) }
        return Cidr.anyContains(networks, address)
    }

    // --- routes -------------------------------------------------------------------------------

    private suspend fun route(run: Run, socket: Socket, request: HttpRequest, body: BodyReader): Outcome = try {
        when {
            request.method == "OPTIONS" -> respond(HttpResponse.empty(204))
            request.path == "/ws" -> upgrade(run, request)
            request.path == "/api/login" -> respond(login(run, socket, request, body))
            request.path == "/api/logout" -> respond(logout(request))
            request.path == "/api/session" -> respond(session(request))
            request.path.startsWith("/a/") -> respond(attachment(request))
            request.path == "/a" -> respond(upload(request, body))
            request.path.startsWith("/api/") -> respond(HttpResponse.error(404))
            request.method == "GET" || request.method == "HEAD" -> respond(static(request))
            else -> respond(HttpResponse.error(405))
        }
    } catch (e: HttpError) {
        respond(HttpResponse.error(e.status, e.message ?: HttpResponse.reason(e.status)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: EOFException) {
        respond(HttpResponse.error(400, "Body ended early"))
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        respond(HttpResponse.error(500, backend.describe(e)))
    }

    private fun respond(response: HttpResponse): Outcome = Outcome.Response(response)

    private fun upgrade(run: Run, request: HttpRequest): Outcome {
        val acceptKey = WsHandshake.accept(request)
        val session = sessions.find(request.cookie(COOKIE)) ?: throw HttpError(401, "Log in first")
        if (backend.me.value == null) throw HttpError(503, "The phone is not set up")
        if (clientSessions.size >= run.config.maxClients) throw HttpError(503, "Client limit reached")
        return Outcome.Upgrade(acceptKey, session.username)
    }

    private suspend fun login(run: Run, socket: Socket, request: HttpRequest, body: BodyReader): HttpResponse {
        if (request.method != "POST") return HttpResponse.error(405)
        val address = socket.inetAddress?.hostAddress.orEmpty()
        val wait = throttle.lockedFor(address)
        if (wait > 0) return HttpResponse.json(429, jsonError("Too many attempts. Try again in ${(wait + 999) / 1000} s."), "Retry-After" to ((wait + 999) / 1000).toString())
        val bytes = withContext(io) { body.readAll(Limits.MAX_JSON_BODY_BYTES) }
        val fields = parseJsonObject(bytes) ?: return HttpResponse.json(400, jsonError("Send a JSON object with username and password"))
        val username = fields.string("username") ?: return HttpResponse.json(400, jsonError("username is required"))
        val password = fields.string("password") ?: return HttpResponse.json(400, jsonError("password is required"))
        val config = run.config
        val isUser = MessageDigest.isEqual(username.toByteArray(Charsets.UTF_8), config.username.toByteArray(Charsets.UTF_8))
        val isPassword = Passwords.verify(password, config.password)
        if (!isUser || !isPassword) {
            throttle.recordFailure(address)
            return HttpResponse.json(401, jsonError("Wrong username or password"))
        }
        throttle.clear(address)
        if (clientSessions.size >= config.maxClients) return HttpResponse.json(503, jsonError("Client limit reached"))
        val session = sessions.create(config.username, address)
        return HttpResponse.empty(204, "Set-Cookie" to "$COOKIE=${session.token}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${Limits.SESSION_IDLE_MS / 1000}")
    }

    private fun logout(request: HttpRequest): HttpResponse {
        if (request.method != "POST") return HttpResponse.error(405)
        sessions.remove(request.cookie(COOKIE))
        return HttpResponse.empty(204, "Set-Cookie" to "$COOKIE=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0")
    }

    private fun session(request: HttpRequest): HttpResponse {
        if (request.method != "GET" && request.method != "HEAD") return HttpResponse.error(405)
        sessions.find(request.cookie(COOKIE)) ?: return HttpResponse.error(401)
        val me = backend.me.value ?: return HttpResponse.error(503, "The phone is not set up")
        val json = buildJsonObject {
            put("name", me.name)
            put("ip", me.ip)
        }
        return HttpResponse.json(200, DexJson.encodeToString(JsonObject.serializer(), json))
    }

    private suspend fun attachment(request: HttpRequest): HttpResponse {
        if (request.method != "GET" && request.method != "HEAD") return HttpResponse.error(405)
        sessions.find(request.cookie(COOKIE)) ?: return HttpResponse.error(401)
        val id = request.path.removePrefix("/a/")
        if (id.isEmpty() || id.length > MAX_ID_CHARS || '/' in id) return HttpResponse.error(404)
        val found = backend.attachment(id) ?: return HttpResponse.error(404)
        val file = found.file
        val length = withContext(io) { if (file.isFile) file.length() else -1L }
        if (length < 0) return HttpResponse.error(404)
        val disposition = "Content-Disposition" to HttpResponse.contentDisposition(found.name)
        val cache = "Cache-Control" to "private, no-cache"
        return when (val range = Ranges.parse(request.header("range"), length)) {
            RangeResult.None -> HttpResponse(200, streamOf(file, 0, length), found.mime, listOf(disposition, cache, "Accept-Ranges" to "bytes"))
            RangeResult.Unsatisfiable -> HttpResponse(416, HttpBody.Empty, null, listOf("Content-Range" to "bytes */$length"))
            is RangeResult.Satisfiable -> HttpResponse(
                206,
                streamOf(file, range.range.start, range.range.length),
                found.mime,
                listOf(disposition, cache, "Accept-Ranges" to "bytes", "Content-Range" to "bytes ${range.range.start}-${range.range.endInclusive}/$length"),
            )
        }
    }

    private fun streamOf(file: File, offset: Long, length: Long): HttpBody.Stream = HttpBody.Stream(length) {
        FileInputStream(file).also { stream ->
            var toSkip = offset
            while (toSkip > 0) {
                val skipped = stream.skip(toSkip)
                if (skipped <= 0) throw EOFException("file shorter than its range")
                toSkip -= skipped
            }
        }
    }

    private suspend fun upload(request: HttpRequest, body: BodyReader): HttpResponse {
        if (request.method != "POST") return HttpResponse.error(405)
        sessions.find(request.cookie(COOKIE)) ?: return HttpResponse.error(401)
        if (request.header("content-length") == null) return HttpResponse.error(411)
        val length = request.contentLength
        if (length <= 0) return HttpResponse.error(400, "Empty upload")
        if (length > Limits.MAX_UPLOAD_BYTES) return HttpResponse.error(413, "File is too large")
        val q = request.query
        val peer = q["peer"]?.trim()?.takeIf { it.length in 1..MAX_PEER_CHARS && it.all { c -> c.isLetterOrDigit() || c == '.' || c == ':' } }
            ?: return HttpResponse.error(400, "Bad peer address")
        val name = sanitizeName(q["name"])
        val mime = q["mime"]?.trim()?.takeIf { MIME.matches(it) } ?: DEFAULT_MIME
        val replyTo = q["reply"]?.takeIf { it.isNotEmpty() }?.also { if (it.length > MAX_ID_CHARS) return HttpResponse.error(400, "Bad reply id") }
        val file = backend.newUploadFile(name)
        try {
            withContext(io) {
                file.parentFile?.mkdirs()
                file.outputStream().use { sink -> body.copyTo(sink) }
            }
            backend.sendUpload(
                DexUpload(
                    peer = peer,
                    file = file,
                    name = name,
                    mime = mime,
                    size = length,
                    isVoice = q["voice"] == "1",
                    durationMs = q["durationMs"]?.toLongOrNull()?.takeIf { it >= 0 },
                    replyTo = replyTo,
                    isCovered = q["cover"] == "1",
                ),
            )
        } catch (e: Exception) {
            withContext(io) { file.delete() }
            throw e
        }
        return HttpResponse.json(201, "{}")
    }

    private fun static(request: HttpRequest): HttpResponse {
        val relative = if (request.path == "/") INDEX else request.path.removePrefix("/")
        if (relative.isEmpty() || relative.split('/').any { !SEGMENT.matches(it) }) return HttpResponse.error(404)
        val asset = assets.open(relative) ?: return HttpResponse.error(404)
        val cache = when {
            relative == INDEX -> "no-store"
            HASHED.containsMatchIn(relative) -> "public, max-age=31536000, immutable"
            else -> "no-cache"
        }
        return HttpResponse(200, HttpBody.Stream(asset.length, asset.open), asset.mime, listOf("Cache-Control" to cache))
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun upgradeHead(acceptKey: String): ByteArray =
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $acceptKey\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

    private fun newClientId(): String = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun parseJsonObject(bytes: ByteArray): JsonObject? = try {
        DexJson.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun jsonError(message: String): String = DexJson.encodeToString(JsonObject.serializer(), buildJsonObject { put("error", message) })

    private fun sanitizeName(raw: String?): String {
        val cleaned = raw.orEmpty().trim().replace(Regex("[\\\\/\\u0000-\\u001F]"), "_").trim('.', ' ')
        return (if (cleaned.isEmpty()) "file" else cleaned).take(MAX_NAME_CHARS)
    }

    private companion object {
        const val COOKIE = "dex"
        const val INDEX = "index.html"
        const val BACKLOG = 16
        const val BIND_ATTEMPTS = 5
        const val BIND_RETRY_MS = 200L
        const val INPUT_BUFFER = 8 * 1024
        const val OUTPUT_BUFFER = 16 * 1024
        const val MAX_USER_AGENT_CHARS = 200
        const val MAX_ID_CHARS = 64
        const val MAX_PEER_CHARS = 45
        const val MAX_NAME_CHARS = 200
        const val DEFAULT_MIME = "application/octet-stream"
        val MIME = Regex("[A-Za-z0-9!#$&^_.+-]{1,64}/[A-Za-z0-9!#$&^_.+-]{1,100}")
        val SEGMENT = Regex("[A-Za-z0-9._-]+")
        val HASHED = Regex("\\.[0-9a-f]{8,}\\.")

        fun closeQuietly(socket: Socket) {
            try {
                socket.close()
            } catch (e: IOException) {
                // already closed
            }
        }

        fun closeQuietly(socket: ServerSocket) {
            try {
                socket.close()
            } catch (e: IOException) {
                // already closed
            }
        }
    }
}

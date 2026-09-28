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
import com.telenebula.dex.http.Authority
import com.telenebula.dex.http.HttpWriter
import com.telenebula.dex.http.LanAdmission
import com.telenebula.dex.http.MediaTypes
import com.telenebula.dex.http.OriginPolicy
import com.telenebula.dex.http.RangeResult
import com.telenebula.dex.http.Ranges
import com.telenebula.dex.http.UploadBudget
import com.telenebula.dex.http.WsClose
import com.telenebula.dex.http.WsHandshake
import com.telenebula.dex.wire.DexCallPhase
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
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ServerSocketFactory
import javax.net.ssl.SSLServerSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    private val admission: LanAdmission,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private class Run(@Volatile var config: DexConfig, val job: Job) {
        @Volatile var serverSocket: ServerSocket? = null
        val connections = HashSet<Socket>()
        val hasReportedRefusal = java.util.concurrent.atomic.AtomicBoolean(false)
        private var reservedClients = 0

        fun register(socket: Socket): Boolean = synchronized(connections) {
            if (connections.size >= Limits.MAX_CONNECTIONS) return false
            connections.add(socket)
        }

        fun unregister(socket: Socket) = synchronized(connections) { connections.remove(socket) }

        /** Taken before the 101 is written, so two upgrades racing for the last place cannot both win. */
        fun reserveClient(): Boolean = synchronized(connections) {
            if (reservedClients >= config.maxClients) return false
            reservedClients += 1
            true
        }

        fun releaseClient() = synchronized(connections) { reservedClients -= 1 }

        val clientCount: Int get() = synchronized(connections) { reservedClients }

        fun closeAll() {
            val open = synchronized(connections) { connections.toList().also { connections.clear() } }
            for (socket in open) closeQuietly(socket)
            serverSocket?.let { closeQuietly(it) }
        }
    }

    private sealed interface Outcome {
        class Response(val response: HttpResponse) : Outcome
        /** a client place is already reserved; whoever takes this outcome releases it */
        class Upgrade(val acceptKey: String, val token: String) : Outcome
    }

    private val lock = Any()
    private var run: Run? = null
    private val sessions = Sessions(now)
    private val throttle = LoginThrottle(now)
    private val clientSessions = ConcurrentHashMap<String, ClientSession>()
    private val random = SecureRandom()
    private val passwordChecks = AtomicInteger(0)
    private val uploads = UploadBudget()

    private val mutableState = MutableStateFlow<DexServerState>(DexServerState.Off)
    val state: StateFlow<DexServerState> = mutableState.asStateFlow()

    private val mutableClients = MutableStateFlow<List<DexClient>>(emptyList())
    val clients: StateFlow<List<DexClient>> = mutableClients.asStateFlow()

    private val faultFlow = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** failures with nobody to answer: a connection handler that blew up, a bind that was lost */
    val faults: SharedFlow<String> = faultFlow.asSharedFlow()

    /** Starts, or re-applies [config]; a port change restarts, new credentials end every login, a lower limit closes the newest sockets. */
    fun start(config: DexConfig) {
        val cleaned = DexConfig(config.port, config.username, config.password, config.maxClients.coerceIn(1, Limits.MAX_CLIENTS))
        synchronized(lock) {
            val current = run
            if (current != null) {
                if (current.config.port == cleaned.port) {
                    val isNewCredential = !isSameCredential(current.config, cleaned)
                    current.config = cleaned
                    if (isNewCredential) {
                        sessions.clear()
                        closeClients(WsClose.POLICY, UNAUTHORIZED) { true }
                    } else {
                        val excess = clientSessions.values.sortedByDescending { it.client.connectedAt }.take((clientSessions.size - cleaned.maxClients).coerceAtLeast(0)).map { it.client.id }.toSet()
                        closeClients(WsClose.TRY_AGAIN_LATER, "client limit") { it.client.id in excess }
                    }
                    return
                }
                stopLocked(current)
            }
            sessions.clear()
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
        turn.revokeAll()
        mutableState.value = DexServerState.Off
    }

    fun send(clientId: String, frame: ServerFrame): Boolean = clientSessions[clientId]?.send(frame) ?: false

    fun broadcast(frame: ServerFrame) {
        for (session in clientSessions.values) session.send(frame)
    }

    /** Ends the login [clientId] used: its sockets close and the browser must sign in again. False when no such client. */
    fun disconnect(clientId: String): Boolean {
        val token = clientSessions[clientId]?.sessionToken ?: return false
        endSession(token)
        return true
    }

    private fun endSession(token: String) {
        sessions.remove(token)
        closeClients(WsClose.POLICY, UNAUTHORIZED) { it.sessionToken == token }
    }

    private fun closeClients(code: Int, reason: String, which: (ClientSession) -> Boolean) {
        for (session in clientSessions.values.filter(which)) session.close(code, reason)
    }

    /** A socket's own activity keeps its login from idling out; the absolute lifetime, eviction and logout do not wait. */
    private fun closeEndedSessions() {
        val live = clientSessions.values.map { it.sessionToken }.toSet().filter { sessions.find(it) != null }.toSet()
        closeClients(WsClose.POLICY, UNAUTHORIZED) { it.sessionToken !in live }
    }

    private fun isSameCredential(a: DexConfig, b: DexConfig): Boolean =
        a.username == b.username && a.password.algorithm == b.password.algorithm && a.password.iterations == b.password.iterations &&
            a.password.saltB64 == b.password.saltB64 && a.password.hashB64 == b.password.hashB64

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
        runScope.launch {
            while (isActive) {
                delay(Limits.SESSION_CHECK_MS)
                closeEndedSessions()
            }
        }
        runScope.launch {
            backend.callState.collect { if (it.phase == DexCallPhase.IDLE || it.phase == DexCallPhase.ENDED) turn.revokeAll() }
        }
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
        val backoff = Backoff("Dex accept") { faultFlow.tryEmit(it) }
        try {
            while (isActive) {
                val socket = try {
                    withContext(io) { server.accept() }.also { backoff.reset() }
                } catch (e: IOException) {
                    if (server.isClosed || !isActive) break
                    backoff.failed(e)
                    continue
                }
                // closed before the TLS handshake: an answer would cost a handshake, and a flood is what fills this
                if (!run.register(socket)) {
                    closeQuietly(socket)
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

    // --- one connection -----------------------------------------------------------------------

    private suspend fun handleConnection(run: Run, socket: Socket) {
        try {
            val remote = socket.inetAddress
            val local = socket.localAddress
            if (remote == null || local == null || isOverlay(remote)) return
            if (!admission.admits(local)) {
                if (run.hasReportedRefusal.compareAndSet(false, true)) {
                    faultFlow.tryEmit("Dex refused ${remote.hostAddress}: it reached ${local.hostAddress}, which is not one of this phone's Wi‑Fi, hotspot, USB or Bluetooth addresses (${admission.known.joinToString { it.hostAddress.orEmpty() }})")
                }
                return
            }
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
                        try {
                            withContext(io) {
                                output.write(upgradeHead(outcome.acceptKey))
                                output.flush()
                                socket.soTimeout = (Limits.WS_PING_INTERVAL_MS * 3).toInt()
                            }
                            serveClient(socket, input, output, request, outcome.token)
                        } finally {
                            run.releaseClient()
                        }
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

    private suspend fun serveClient(socket: Socket, input: InputStream, output: OutputStream, request: HttpRequest, token: String) {
        val client = DexClient(
            id = newClientId(),
            remoteAddress = socket.inetAddress?.hostAddress.orEmpty(),
            userAgent = request.header("user-agent").orEmpty().take(MAX_USER_AGENT_CHARS),
            connectedAt = now(),
        )
        val local = socket.localAddress
        val session = ClientSession(
            client = client,
            sessionToken = token,
            socket = socket,
            input = input,
            output = output,
            backend = backend,
            iceServers = { iceServersFor(local, client.id) },
            onCallReleased = { turn.revoke(client.id) },
            io = io,
        )
        clientSessions[client.id] = session
        publishClients()
        // a logout that landed between the upgrade and this registration is caught here
        if (sessions.find(token) == null) session.close(WsClose.POLICY, UNAUTHORIZED)
        try {
            session.run()
        } finally {
            clientSessions.remove(client.id)
            publishClients()
            turn.revoke(client.id)
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

    /** A relay credential for this browser's call only, naming the one peer it may reach. */
    private fun iceServersFor(local: InetAddress?, clientId: String): List<DexIceServer> {
        val host = local?.let { hostForUrl(it) } ?: return emptyList()
        val peer = backend.callState.value.peer?.ip?.let(Cidr::literal) ?: return emptyList()
        val credential = turn.issue(clientId, peer)
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
        val authority = OriginPolicy.authorityOf(request, socket.localPort) ?: throw HttpError(400, "Bad Host")
        when {
            request.method == "OPTIONS" -> respond(HttpResponse.empty(204))
            request.path == "/ws" -> upgrade(run, request, authority)
            request.path == "/api/login" -> respond(login(run, socket, request, body, authority))
            request.path == "/api/logout" -> respond(logout(request, authority))
            request.path == "/api/session" -> respond(session(request, authority))
            request.path.startsWith("/a/") -> respond(attachment(request, authority))
            request.path == "/a" -> respond(upload(request, body, authority))
            request.path.startsWith("/api/") -> respond(HttpResponse.error(404))
            request.method == "GET" || request.method == "HEAD" -> respond(static(request, authority))
            else -> respond(HttpResponse.error(405))
        }
    } catch (e: HttpError) {
        respond(HttpResponse.error(e.status, e.message ?: HttpResponse.reason(e.status)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: DexException) {
        if (e.kind == DexFailure.REFUSED) respond(HttpResponse.error(409, e.message ?: HttpResponse.reason(409))) else respond(internalError(e))
    } catch (e: EOFException) {
        respond(HttpResponse.error(400, "Body ended early"))
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        respond(internalError(e))
    }

    /** The browser learns only that it failed; what failed is the phone's to know, through its notices. */
    private fun internalError(e: Exception): HttpResponse {
        faultFlow.tryEmit("Dex request failed: ${backend.describe(e)}")
        return HttpResponse.error(500)
    }

    private fun respond(response: HttpResponse): Outcome = Outcome.Response(response)

    private fun upgrade(run: Run, request: HttpRequest, authority: Authority): Outcome {
        val acceptKey = WsHandshake.accept(request)
        if (!OriginPolicy.mayUpgrade(request, authority)) throw HttpError(403, "Origin not allowed")
        val session = sessions.find(request.cookie(COOKIE)) ?: throw HttpError(401, "Log in first")
        if (backend.me.value == null) throw HttpError(503, "The phone is not set up")
        if (!run.reserveClient()) throw HttpError(503, "Client limit reached")
        return Outcome.Upgrade(acceptKey, session.token)
    }

    private suspend fun login(run: Run, socket: Socket, request: HttpRequest, body: BodyReader, authority: Authority): HttpResponse {
        if (request.method != "POST") return HttpResponse.error(405)
        if (!OriginPolicy.mayAct(request, authority)) return HttpResponse.error(403, "Origin not allowed")
        val address = socket.inetAddress?.hostAddress.orEmpty()
        val wait = throttle.lockedFor(address)
        if (wait > 0) return HttpResponse.json(429, jsonError("Too many attempts. Try again in ${(wait + 999) / 1000} s."), "Retry-After" to ((wait + 999) / 1000).toString())
        val bytes = withContext(io) { body.readAll(Limits.MAX_JSON_BODY_BYTES) }
        val fields = parseJsonObject(bytes) ?: return HttpResponse.json(400, jsonError("Send a JSON object with username and password"))
        val username = fields.string("username") ?: return HttpResponse.json(400, jsonError("username is required"))
        val password = fields.string("password") ?: return HttpResponse.json(400, jsonError("password is required"))
        val config = run.config
        val isUser = MessageDigest.isEqual(username.toByteArray(Charsets.UTF_8), config.username.toByteArray(Charsets.UTF_8))
        if (passwordChecks.incrementAndGet() > Limits.MAX_PASSWORD_CHECKS) {
            passwordChecks.decrementAndGet()
            return HttpResponse.json(503, jsonError("The phone is busy. Try again."), "Retry-After" to "1")
        }
        val isPassword = try {
            Passwords.verify(password, config.password)
        } finally {
            passwordChecks.decrementAndGet()
        }
        if (!isUser || !isPassword) {
            throttle.recordFailure(address)
            return HttpResponse.json(401, jsonError("Wrong username or password"))
        }
        throttle.clear(address)
        if (run.clientCount >= config.maxClients) return HttpResponse.json(503, jsonError("Client limit reached"))
        val session = sessions.create(config.username, address)
        closeEndedSessions()
        val maxAge = sessions.remainingMs(session.token) / 1000
        return HttpResponse.empty(204, "Set-Cookie" to "$COOKIE=${session.token}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=$maxAge")
    }

    private fun logout(request: HttpRequest, authority: Authority): HttpResponse {
        if (request.method != "POST") return HttpResponse.error(405)
        if (!OriginPolicy.mayAct(request, authority)) return HttpResponse.error(403, "Origin not allowed")
        request.cookie(COOKIE)?.let(::endSession)
        return HttpResponse.empty(204, "Set-Cookie" to "$COOKIE=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0")
    }

    private fun session(request: HttpRequest, authority: Authority): HttpResponse {
        if (request.method != "GET" && request.method != "HEAD") return HttpResponse.error(405)
        if (!OriginPolicy.mayRead(request, authority)) return HttpResponse.error(403, "Origin not allowed")
        sessions.find(request.cookie(COOKIE)) ?: return HttpResponse.error(401)
        val me = backend.me.value ?: return HttpResponse.error(503, "The phone is not set up")
        val json = buildJsonObject {
            put("name", me.name)
            put("ip", me.ip)
        }
        return HttpResponse.json(200, DexJson.encodeToString(JsonObject.serializer(), json))
    }

    /** Inline only as passive media whose bytes agree with its declared type; anything else is an opaque download. */
    private suspend fun attachment(request: HttpRequest, authority: Authority): HttpResponse {
        if (request.method != "GET" && request.method != "HEAD") return HttpResponse.error(405)
        if (!OriginPolicy.mayRead(request, authority)) return HttpResponse.error(403, "Origin not allowed")
        sessions.find(request.cookie(COOKIE)) ?: return HttpResponse.error(401)
        val id = request.path.removePrefix("/a/")
        if (id.isEmpty() || id.length > MAX_ID_CHARS || '/' in id) return HttpResponse.error(404)
        val found = backend.attachment(id) ?: return HttpResponse.error(404)
        val file = found.file
        val length = withContext(io) { if (file.isFile) file.length() else -1L }
        if (length < 0) return HttpResponse.error(404)
        val head = withContext(io) { readHead(file) }
        val inline = MediaTypes.inlineType(found.mime, head)
        val type = inline ?: MediaTypes.DOWNLOAD
        val disposition = "Content-Disposition" to HttpResponse.contentDisposition(found.name, isInline = inline != null)
        val cache = "Cache-Control" to "private, no-store"
        return when (val range = Ranges.parse(request.header("range"), length)) {
            RangeResult.None -> HttpResponse(200, streamOf(file, 0, length), type, listOf(disposition, cache, "Accept-Ranges" to "bytes"))
            RangeResult.Unsatisfiable -> HttpResponse(416, HttpBody.Empty, null, listOf("Content-Range" to "bytes */$length"))
            is RangeResult.Satisfiable -> HttpResponse(
                206,
                streamOf(file, range.range.start, range.range.length),
                type,
                listOf(disposition, cache, "Accept-Ranges" to "bytes", "Content-Range" to "bytes ${range.range.start}-${range.range.endInclusive}/$length"),
            )
        }
    }

    private fun readHead(file: File): ByteArray = FileInputStream(file).use { stream ->
        val buffer = ByteArray(MediaTypes.SNIFF_BYTES)
        var read = 0
        while (read < buffer.size) {
            val n = stream.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        buffer.copyOf(read)
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

    /** Everything that can refuse the upload does so before the first byte is staged; a partial file never outlives its request. */
    private suspend fun upload(request: HttpRequest, body: BodyReader, authority: Authority): HttpResponse {
        if (request.method != "POST") return HttpResponse.error(405)
        if (!OriginPolicy.mayAct(request, authority)) return HttpResponse.error(403, "Origin not allowed")
        val session = sessions.find(request.cookie(COOKIE)) ?: return HttpResponse.error(401)
        if (request.header("content-length") == null) return HttpResponse.error(411)
        val length = request.contentLength
        if (length <= 0) return HttpResponse.error(400, "Empty upload")
        if (length > Limits.MAX_UPLOAD_BYTES) return HttpResponse.error(413, "File is too large")
        val q = request.query
        val peer = q["peer"]?.trim()?.takeIf { it.length in 1..MAX_PEER_CHARS && it.all { c -> c.isLetterOrDigit() || c == '.' || c == ':' } }
            ?: return HttpResponse.error(400, "Bad peer address")
        val name = sanitizeName(q["name"])
        val mime = q["mime"]?.let(MediaTypes::normalise) ?: DEFAULT_MIME
        val replyTo = q["reply"]?.takeIf { it.isNotEmpty() }?.also { if (it.length > MAX_ID_CHARS || it.any { c -> c < ' ' }) return HttpResponse.error(400, "Bad reply id") }
        val ticket = uploads.reserve(session.token, length)
            ?: return HttpResponse(503, HttpBody.Bytes("Too many uploads at once. Try again shortly.".toByteArray()), "text/plain; charset=utf-8", listOf("Retry-After" to "5"), isKeepAliveAllowed = false)
        ticket.use {
            // every upload in flight, this one included, is counted as if it were already on disk
            if (backend.freeBytes() - uploads.stagedBytes < Limits.UPLOAD_FREE_SPACE_RESERVE) return HttpResponse.error(507, "Not enough free space on the phone")
            backend.checkUpload(peer)
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
            } catch (e: Throwable) {
                withContext(NonCancellable + io) { file.delete() }
                throw e
            }
        }
        return HttpResponse.json(201, "{}")
    }

    private fun static(request: HttpRequest, authority: Authority): HttpResponse {
        val relative = if (request.path == "/") INDEX else request.path.removePrefix("/")
        if (relative.isEmpty() || relative.split('/').any { !SEGMENT.matches(it) }) return HttpResponse.error(404)
        val asset = assets.open(relative) ?: return HttpResponse.error(404)
        val cache = when {
            relative == INDEX -> "no-store"
            HASHED.containsMatchIn(relative) -> "public, max-age=31536000, immutable"
            else -> "no-cache"
        }
        val headers = buildList {
            add("Cache-Control" to cache)
            if (relative == INDEX) add("Content-Security-Policy" to HttpWriter.pageCsp(authority))
        }
        return HttpResponse(200, HttpBody.Stream(asset.length, asset.open), asset.mime, headers)
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
        val cleaned = raw.orEmpty().trim().replace(Regex("[\\\\/\\u0000-\\u001F\\u007F-\\u009F\\u202A-\\u202E\\u2066-\\u2069]"), "_").trim('.', ' ')
        return (if (cleaned.isEmpty()) "file" else cleaned).take(MAX_NAME_CHARS)
    }

    private companion object {
        /** `__Host-` makes the browser refuse it unless it is Secure, host-only and for the whole site */
        const val COOKIE = "__Host-dex"
        const val UNAUTHORIZED = "unauthorized"
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
        val SEGMENT = Regex("[A-Za-z0-9._-]+")
        val HASHED = Regex("(?:^|/)index-[A-Za-z0-9_-]{8,}\\.(?:js|css)$")

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

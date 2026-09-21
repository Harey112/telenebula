package com.telenebula.dex.http

import com.telenebula.dex.DexBackend
import com.telenebula.dex.DexCallCommand
import com.telenebula.dex.DexCallEvent
import com.telenebula.dex.DexClient
import com.telenebula.dex.Limits
import com.telenebula.dex.wire.ClientFrame
import com.telenebula.dex.wire.DexIceServer
import com.telenebula.dex.wire.DexJson
import com.telenebula.dex.wire.ServerFrame
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * One browser on one socket: the subscriptions it is fed, the commands it sends, and the frames
 * queued for it. Everything it owns is a child of [run]; when the socket closes it is all gone.
 */
class ClientSession(
    val client: DexClient,
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    private val backend: DexBackend,
    private val iceServers: () -> List<DexIceServer>,
    private val io: CoroutineDispatcher,
) {
    private sealed interface Outgoing {
        class Frame(val frame: ServerFrame) : Outgoing
        class Ping(val payload: ByteArray) : Outgoing
        class Pong(val payload: ByteArray) : Outgoing
        class Close(val code: Int, val reason: String) : Outgoing
    }

    private val out = Channel<Outgoing>(Limits.WS_OUT_QUEUE)
    private val isClosing = AtomicBoolean(false)
    private val unansweredPings = AtomicInteger(0)
    private val chatLock = Any()
    private val openChats = LinkedHashMap<String, Job>(8, 0.75f, true)
    private val watchLock = Any()
    private val watching = HashMap<String, Job>()

    /** False once the browser is too slow to keep up; it is then closed and resyncs on reconnect. */
    fun send(frame: ServerFrame): Boolean {
        if (isClosing.get()) return false
        if (out.trySend(Outgoing.Frame(frame)).isSuccess) return true
        close(WsClose.TRY_AGAIN_LATER, "too slow")
        return false
    }

    /** Idempotent; the close frame goes out if the queue has room, the socket closes either way. */
    fun close(code: Int, reason: String) {
        if (!isClosing.compareAndSet(false, true)) return
        if (out.trySend(Outgoing.Close(code, reason)).isFailure) closeSocket()
        out.close()
    }

    private var workers: CoroutineScope? = null

    suspend fun run() {
        try {
            coroutineScope {
                val workJob = SupervisorJob(coroutineContext[Job])
                val work = CoroutineScope(coroutineContext + workJob)
                workers = work
                work.launch { pump() }
                work.launch { ping() }
                try {
                    hello()
                    if (!isClosing.get()) {
                        work.subscribe()
                        read()
                    }
                } finally {
                    workJob.cancel()
                    closeSocket()
                }
            }
        } finally {
            isClosing.set(true)
            out.close()
            closeSocket()
        }
    }

    // --- outbound ---------------------------------------------------------------------------

    private suspend fun pump() {
        try {
            for (item in out) {
                val bytes = when (item) {
                    is Outgoing.Frame -> WsCodec.encodeText(DexJson.encodeToString(ServerFrame.serializer(), item.frame))
                    is Outgoing.Ping -> WsCodec.encode(WsOpcode.PING, item.payload)
                    is Outgoing.Pong -> WsCodec.encode(WsOpcode.PONG, item.payload)
                    is Outgoing.Close -> WsCodec.encodeClose(item.code, item.reason)
                }
                withContext(io) {
                    output.write(bytes)
                    output.flush()
                }
                if (item is Outgoing.Close) break
            }
        } catch (e: IOException) {
            // the reader sees the same closed socket and ends the session
        } finally {
            closeSocket()
        }
    }

    private suspend fun ping() {
        while (true) {
            delay(Limits.WS_PING_INTERVAL_MS)
            if (unansweredPings.incrementAndGet() > 2) {
                close(WsClose.GOING_AWAY, "unresponsive")
                return
            }
            if (out.trySend(Outgoing.Ping(ByteArray(0))).isFailure) return
        }
    }

    private suspend fun hello() {
        val me = backend.me.value
        if (me == null) {
            close(WsClose.POLICY, "phone is not set up")
            return
        }
        val free = try {
            backend.freeBytes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0L
        }
        send(ServerFrame.Hello(me, client.id, free))
    }

    private fun CoroutineScope.subscribe() {
        feed("chats", backend.chats()) { ServerFrame.Chats(it) }
        feed("contacts", backend.contacts()) { ServerFrame.Contacts(it) }
        feed("presence", backend.presence()) { ServerFrame.Presence(it) }
        feed("typing", backend.typing()) { ServerFrame.Typing(it.toList()) }
        feed("queues", backend.queues()) { ServerFrame.Queues(it) }
        feed("call", backend.callState) { ServerFrame.CallState(it) }
        feed("call", backend.callEvents.filter { it.clientId == client.id }) { toFrame(it) }
        feed("settings", backend.settings()) { ServerFrame.Settings(it) }
    }

    /**
     * The sections a browser is looking at. Each is a query the phone would rather not run for a
     * tab nobody is on, so one starts when it is asked for and is cancelled the moment it is not.
     */
    private fun CoroutineScope.watch(sections: List<String>) {
        val wanted = sections.filter { it in WATCHABLE }.toSet()
        val started = ArrayList<Pair<String, Job>>()
        val stopped = ArrayList<Job>()
        synchronized(watchLock) {
            for (section in watching.keys.toList()) {
                if (section !in wanted) watching.remove(section)?.let(stopped::add)
            }
            for (section in wanted) {
                if (watching.containsKey(section)) continue
                val job = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { collect(section) }
                watching[section] = job
                started.add(section to job)
            }
        }
        for (job in stopped) job.cancel()
        for ((_, job) in started) job.start()
    }

    private suspend fun collect(section: String) {
        val flow: Flow<ServerFrame> = when (section) {
            "account" -> backend.account().map { ServerFrame.Account(it) }
            "network" -> backend.network().map { ServerFrame.Network(it) }
            "storage" -> backend.storage().map { ServerFrame.Storage(it) }
            "diagnostics" -> backend.diagnostics().map { ServerFrame.Diagnostics(it) }
            "updates" -> backend.updates().map { ServerFrame.Updates(it) }
            else -> return
        }
        flow.catch { e -> send(ServerFrame.Error(backend.describe(e), section)) }.collect { send(it) }
    }

    private fun <T> CoroutineScope.feed(ref: String, flow: Flow<T>, map: (T) -> ServerFrame): Job = launch {
        flow.catch { e -> send(ServerFrame.Error(backend.describe(e), ref)) }.collect { send(map(it)) }
    }

    private fun toFrame(event: DexCallEvent): ServerFrame = when (event) {
        is DexCallEvent.Media -> ServerFrame.CallMedia(
            callId = event.callId,
            role = if (event.isOfferer) ServerFrame.ROLE_OFFERER else ServerFrame.ROLE_ANSWERER,
            video = event.video,
            iceServers = iceServers(),
            remoteSdp = event.remoteSdp,
            remoteSdpType = event.remoteSdpType,
            isRestart = event.isRestart,
        )
        is DexCallEvent.Sdp -> ServerFrame.CallSdp(event.callId, event.sdp, event.sdpType)
        is DexCallEvent.Ice -> ServerFrame.CallIce(event.callId, event.candidate)
        is DexCallEvent.Release -> ServerFrame.CallRelease(event.callId, event.reason)
    }

    // --- inbound ----------------------------------------------------------------------------

    private suspend fun read() {
        val assembler = WsAssembler()
        try {
            while (!isClosing.get()) {
                val frame = withContext(io) { WsCodec.readFrame(input) }
                unansweredPings.set(0)
                when (val message = assembler.feed(frame) ?: continue) {
                    is WsMessage.Ping -> out.trySend(Outgoing.Pong(message.payload))
                    is WsMessage.Pong -> Unit
                    is WsMessage.Close -> {
                        close(WsClose.NORMAL, "")
                        return
                    }
                    is WsMessage.Text -> handle(message.text)
                }
            }
        } catch (e: WsCloseException) {
            close(e.code, e.reason)
        } catch (e: EOFException) {
            isClosing.set(true)
        } catch (e: IOException) {
            isClosing.set(true)
        }
    }

    private suspend fun handle(text: String) {
        val frame = try {
            DexJson.decodeFromString(ClientFrame.serializer(), text)
        } catch (e: SerializationException) {
            send(ServerFrame.Error("Unknown frame", null))
            return
        } catch (e: IllegalArgumentException) {
            send(ServerFrame.Error("Unknown frame", null))
            return
        }
        dispatch(frame)
    }

    private suspend fun dispatch(frame: ClientFrame) {
        when (frame) {
            ClientFrame.Ping -> send(ServerFrame.Pong)
            is ClientFrame.OpenChat -> command("open_chat") { workers?.openChat(peer(frame.peer)) }
            is ClientFrame.CloseChat -> command("close_chat") { closeChat(peer(frame.peer)) }
            is ClientFrame.LoadMore -> command("load_more") {
                val peer = peer(frame.peer)
                val messages = backend.messagesBefore(peer, frame.beforeTs, id(frame.beforeId), Limits.CHAT_PAGE)
                send(ServerFrame.ChatMore(peer, messages, hasMore = messages.size >= Limits.CHAT_PAGE))
            }
            is ClientFrame.SendText -> command("send_text") {
                backend.sendText(peer(frame.peer), body(frame.body), frame.replyTo?.let(::id), frame.covered)
            }
            is ClientFrame.Typing -> command("typing") { backend.sendTyping(peer(frame.peer), frame.isTyping) }
            is ClientFrame.MarkRead -> command("mark_read") { backend.markRead(peer(frame.peer)) }
            is ClientFrame.React -> command(frame.messageId) { backend.react(id(frame.messageId), emoji(frame.emoji)) }
            is ClientFrame.Edit -> command(frame.messageId) { backend.edit(id(frame.messageId), body(frame.body)) }
            is ClientFrame.Delete -> command(frame.messageId) { backend.delete(id(frame.messageId), frame.forEveryone) }
            is ClientFrame.RetryAction -> command(frame.actionId) { backend.retryAction(id(frame.actionId)) }
            is ClientFrame.CancelAction -> command(frame.actionId) { backend.cancelAction(id(frame.actionId)) }
            is ClientFrame.AcceptOffer -> command(frame.messageId) { backend.acceptOffer(id(frame.messageId)) }
            is ClientFrame.DeclineOffer -> command(frame.messageId) { backend.declineOffer(id(frame.messageId)) }
            is ClientFrame.CancelTransfer -> command(frame.messageId) { backend.cancelTransfer(id(frame.messageId)) }
            is ClientFrame.CallStart -> call { DexCallCommand.Start(client.id, peer(frame.peer), frame.video) }
            is ClientFrame.CallAccept -> call { DexCallCommand.Accept(client.id, id(frame.callId)) }
            is ClientFrame.CallReject -> call { DexCallCommand.Reject(client.id, id(frame.callId)) }
            is ClientFrame.CallEnd -> call { DexCallCommand.End(client.id, id(frame.callId)) }
            is ClientFrame.CallSdp -> call { DexCallCommand.Sdp(client.id, id(frame.callId), sdp(frame.sdp), sdpType(frame.sdpType)) }
            is ClientFrame.CallIce -> call { DexCallCommand.Ice(client.id, id(frame.callId), frame.candidate?.also { candidate(it.candidate) }) }
            is ClientFrame.CallConnected -> call { DexCallCommand.Connected(client.id, id(frame.callId)) }
            is ClientFrame.CallFailed -> call { DexCallCommand.Failed(client.id, id(frame.callId), frame.reason.take(MAX_REASON_CHARS)) }
            is ClientFrame.CallCam -> call { DexCallCommand.Cam(client.id, id(frame.callId), frame.isOn) }
            is ClientFrame.CallMoveToPhone -> call { DexCallCommand.MoveToPhone(client.id, id(frame.callId)) }

            is ClientFrame.Watch -> done("watch") {
                require(frame.sections.size <= WATCHABLE.size) { "Too many sections" }
                workers?.watch(frame.sections)
            }
            is ClientFrame.SetSettings -> done("set_settings") { backend.applySettings(frame.patch) }
            is ClientFrame.SetQuickReaction -> done("set_quick_reaction") { backend.setQuickReaction(frame.slot, emoji(frame.emoji)) }
            is ClientFrame.RequestContactDetail -> command("request_contact_detail") {
                val peer = peer(frame.peer)
                val detail = backend.contactDetail(peer) ?: throw IllegalArgumentException("No contact at $peer")
                send(ServerFrame.ContactDetail(detail))
            }
            is ClientFrame.ContactSave -> done("contact_save") {
                backend.saveContact(peer(frame.peer), text(frame.name), text(frame.nickname), notes(frame.notes))
            }
            is ClientFrame.ContactAdd -> done("contact_add") {
                backend.addContact(peer(frame.peer), text(frame.name), text(frame.nickname), notes(frame.notes))
            }
            is ClientFrame.ContactDelete -> done("contact_delete") { backend.deleteContact(peer(frame.peer)) }
            is ClientFrame.ContactFlagsSet -> done("contact_flags") { backend.setContactFlags(peer(frame.peer), frame.flags) }
            is ClientFrame.ContactPrivacySet -> done("contact_privacy") { backend.setContactPrivacy(peer(frame.peer), frame.privacy) }
            is ClientFrame.ContactNotificationsSet -> done("contact_notifications") { backend.setContactNotifications(peer(frame.peer), frame.prefs) }
            is ClientFrame.ContactChangeIp -> done("contact_change_ip") { backend.changeContactIp(peer(frame.peer), peer(frame.newIp)) }
            is ClientFrame.ClearHistory -> done("clear_history") { backend.clearHistory(peer(frame.peer)) }
            ClientFrame.ClearAllHistory -> done("clear_all_history") { backend.clearAllHistory() }
            ClientFrame.ClearOrphans -> command("clear_orphans") {
                val freed = backend.clearOrphans()
                send(ServerFrame.Done("clear_orphans", "$freed"))
            }
            is ClientFrame.RequestCallLogs -> command("request_call_logs") {
                val peer = frame.peer?.let(::peer)
                send(ServerFrame.CallLogs(backend.callLogs(peer, frame.limit.coerceIn(1, MAX_CALL_LOGS))))
            }
            is ClientFrame.DeleteCallLogs -> done("delete_call_logs") {
                require(frame.ids.size in 1..MAX_CALL_LOGS) { "Bad selection" }
                backend.deleteCallLogs(frame.ids.map(::id))
            }
            is ClientFrame.RequestChatMedia -> command("request_chat_media") {
                val peer = peer(frame.peer)
                send(ServerFrame.ChatMedia(peer, backend.chatMedia(peer)))
            }
            is ClientFrame.RequestChatLinks -> command("request_chat_links") {
                val peer = peer(frame.peer)
                send(ServerFrame.ChatLinks(peer, backend.chatLinks(peer)))
            }
            is ClientFrame.PingPeer -> command("ping_peer") { send(ServerFrame.PingResult(backend.pingPeer(peer(frame.peer)))) }
            is ClientFrame.RetryFailed -> done("retry_failed") { backend.retryFailed(if (frame.peer.isEmpty()) "" else peer(frame.peer)) }
            is ClientFrame.Drain -> done("drain") { backend.drain(peer(frame.peer)) }
            ClientFrame.CheckUpdates -> done("check_updates") { backend.checkUpdates() }
            is ClientFrame.SetTunnel -> done("set_tunnel") { backend.setTunnel(frame.isOn) }
            is ClientFrame.Forward -> done("forward") { backend.forward(id(frame.messageId), peer(frame.peer)) }
            is ClientFrame.Search -> command("search") {
                val peer = peer(frame.peer)
                send(ServerFrame.SearchResults(peer, backend.search(peer, body(frame.text))))
            }
        }
    }

    /** A command with nothing to answer but the fact that it happened. */
    private suspend fun done(what: String, block: suspend () -> Unit) {
        command(what) {
            block()
            send(ServerFrame.Done(what, null))
        }
    }

    /** One backend call; anything but cancellation becomes one error frame to this browser. */
    private suspend fun command(ref: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(ServerFrame.Error(backend.describe(e), ref))
        }
    }

    private suspend fun call(build: () -> DexCallCommand) = command("call") { backend.onCallCommand(build()) }

    private fun CoroutineScope.openChat(peer: String) {
        val evicted: Job?
        val job = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            backend.chat(peer)
                .catch { e -> send(ServerFrame.Error(backend.describe(e), peer)) }
                .collect { send(ServerFrame.Chat(it)) }
        }
        synchronized(chatLock) {
            openChats.remove(peer)?.cancel()
            openChats[peer] = job
            evicted = if (openChats.size > MAX_OPEN_CHATS) openChats.keys.firstOrNull()?.let { openChats.remove(it) } else null
        }
        evicted?.cancel()
        job.start()
    }

    private fun closeChat(peer: String) {
        synchronized(chatLock) { openChats.remove(peer) }?.cancel()
    }

    private fun closeSocket() {
        try {
            socket.close()
        } catch (e: IOException) {
            // already closed
        }
    }

    // --- validation ---------------------------------------------------------------------------

    private fun peer(value: String): String {
        val text = value.trim()
        require(text.length in 1..MAX_PEER_CHARS && text.all { it.isLetterOrDigit() || it == '.' || it == ':' }) { "Bad peer address" }
        return text
    }

    private fun id(value: String): String {
        require(value.length in 1..MAX_ID_CHARS && value.none { it < ' ' }) { "Bad id" }
        return value
    }

    private fun body(value: String): String {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) { "Message is too long" }
        return value
    }

    private fun emoji(value: String): String {
        require(value.length in 1..MAX_EMOJI_CHARS) { "Bad reaction" }
        return value
    }

    private fun sdp(value: String): String {
        require(value.length in 1..MAX_SDP_CHARS) { "Bad session description" }
        return value
    }

    private fun sdpType(value: String): String {
        require(value == "offer" || value == "answer" || value == "pranswer" || value == "rollback") { "Bad session description type" }
        return value
    }

    private fun candidate(value: String) {
        require(value.length <= MAX_CANDIDATE_CHARS) { "Bad candidate" }
    }

    private fun text(value: String): String {
        require(value.length <= MAX_NAME_CHARS && value.none { it < ' ' }) { "That name is too long" }
        return value.trim()
    }

    private fun notes(value: String): String {
        require(value.length <= MAX_NOTES_CHARS) { "Those notes are too long" }
        return value.trim()
    }

    private companion object {
        val WATCHABLE = setOf("account", "network", "storage", "diagnostics", "updates")
        const val MAX_CALL_LOGS = 500
        const val MAX_NAME_CHARS = 200
        const val MAX_NOTES_CHARS = 4 * 1024
        const val MAX_OPEN_CHATS = 4
        const val MAX_PEER_CHARS = 45
        const val MAX_ID_CHARS = 64
        const val MAX_BODY_BYTES = 16 * 1024
        const val MAX_EMOJI_CHARS = 16
        const val MAX_SDP_CHARS = 64 * 1024
        const val MAX_CANDIDATE_CHARS = 512
        const val MAX_REASON_CHARS = 200
    }
}

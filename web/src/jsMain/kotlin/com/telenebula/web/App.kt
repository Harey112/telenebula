package com.telenebula.web

import com.telenebula.web.call.CallMedia
import com.telenebula.web.call.CallMediaPort
import com.telenebula.web.media.Recorder
import com.telenebula.web.net.Api
import com.telenebula.web.net.LoginResult
import com.telenebula.web.net.SessionResult
import com.telenebula.web.net.Socket
import com.telenebula.web.net.SocketStatus
import com.telenebula.web.net.UploadHandle
import com.telenebula.web.net.UploadOutcome
import com.telenebula.web.net.UploadRequest
import com.telenebula.web.state.Actions
import com.telenebula.web.state.AppState
import com.telenebula.web.state.CallFilter
import com.telenebula.web.state.Composer
import com.telenebula.web.state.Connection
import com.telenebula.web.state.Dialog
import com.telenebula.web.state.Prompt
import com.telenebula.web.state.Recording
import com.telenebula.web.state.Screen
import com.telenebula.web.state.SettingsTab
import com.telenebula.web.state.Store
import com.telenebula.web.state.Tab
import com.telenebula.web.state.Toast
import com.telenebula.web.state.Upload
import com.telenebula.web.ui.CallLogsView
import com.telenebula.web.ui.CallsView
import com.telenebula.web.ui.ChatInfoView
import com.telenebula.web.ui.ChatListView
import com.telenebula.web.ui.ContactsView
import com.telenebula.web.ui.ConversationView
import com.telenebula.web.ui.DialogView
import com.telenebula.web.ui.LightboxView
import com.telenebula.web.ui.LoginView
import com.telenebula.web.ui.PromptView
import com.telenebula.web.ui.RailView
import com.telenebula.web.ui.SettingsView
import com.telenebula.web.ui.ShellView
import com.telenebula.web.ui.ToastsView
import com.telenebula.web.ui.Theme
import com.telenebula.web.wire.ClientFrame
import com.telenebula.web.wire.DexCallPhase
import com.telenebula.web.wire.DexChat
import com.telenebula.web.wire.DexContactFlags
import com.telenebula.web.wire.DexContactNotifications
import com.telenebula.web.wire.DexContactPrivacy
import com.telenebula.web.wire.DexDirection
import com.telenebula.web.wire.DexIceCandidate
import com.telenebula.web.wire.DexMessage
import com.telenebula.web.wire.DexMessageKind
import com.telenebula.web.wire.DexNoticeLevel
import com.telenebula.web.wire.DexRevealGate
import com.telenebula.web.wire.DexSettingsPatch
import com.telenebula.web.wire.ServerFrame
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.url.URL
import org.w3c.files.File
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.coroutines.resume

/** The one owner of logic: frames in, state out, commands back to the phone. */
class App(root: HTMLElement) : Actions, CallMediaPort {
    private val scope: CoroutineScope = MainScope()
    private val store = Store(AppState()) { block -> window.requestAnimationFrame { block() } }
    private val socket = Socket(::onFrame, ::onSocketStatus, ::onSocketOpen)
    private val media = CallMedia(scope, this)
    private val recorder = Recorder()
    private val uploadHandles = HashMap<Int, UploadHandle>()
    private var nextId = 1
    private var uploadCount = 0
    private var reconnectChecks = 0
    private var lastMarkRead: Pair<String, Long> = "" to 0L

    private val login = LoginView(root, this)
    private val rail = RailView(root, this)
    private val shell = ShellView(root, this)
    private val chatList = ChatListView(shell.paneHost(), this)
    private val conversation = ConversationView(shell.paneHost(), this)
    private val chatInfo = ChatInfoView(shell.paneHost(), this)
    private val contactsView = ContactsView(shell.paneHost(), this)
    private val callLogs = CallLogsView(shell.paneHost(), this)
    private val settingsView = SettingsView(shell.paneHost(), this)
    private val calls = CallsView(root, shell.callBannerSlot, this)
    private val lightbox = LightboxView(root, this)
    private val dialog = DialogView(root, this)
    private val prompt = PromptView(root, this)
    private val toasts = ToastsView(root, this)

    private val state: AppState get() = store.state

    init {
        store.listen { prev, next ->
            login.render(prev, next)
            rail.render(prev, next)
            shell.render(prev, next)
            chatList.render(prev, next)
            conversation.render(prev, next)
            chatInfo.render(prev, next)
            contactsView.render(prev, next)
            callLogs.render(prev, next)
            settingsView.render(prev, next)
            calls.render(prev, next)
            lightbox.render(prev, next, Api::attachmentUrl)
            dialog.render(prev, next)
            prompt.render(prev, next)
            toasts.render(prev, next)
            if (prev.chats !== next.chats) updateTitle(next)
            if (prev.settings != next.settings) Theme.apply(next.effective)
            if (prev.screen !== next.screen && next.screen is Screen.Login) socket.stop()
        }
        document.addEventListener("keydown", ::onKey)
        document.addEventListener("visibilitychange", { if (!document.asDynamic().hidden.unsafeCast<Boolean>()) markRead() })
    }

    fun boot() {
        scope.launch {
            when (val s = Api.session()) {
                is SessionResult.Active -> {
                    store.update { it.copy(screen = Screen.App, me = s.me) }
                    socket.start()
                    loadEmoji()
                }
                SessionResult.None -> store.update { it.copy(screen = Screen.Login()) }
                is SessionResult.Failed -> store.update { it.copy(screen = Screen.Login(error = "Can't reach the phone: ${s.message}")) }
            }
        }
    }

    private fun loadEmoji() {
        if (state.emoji.isNotEmpty()) return
        scope.launch {
            val groups = Api.emojiCatalog()
            if (groups.isNotEmpty()) store.update { it.copy(emoji = groups) }
        }
    }

    // --- socket ---------------------------------------------------------------------------------

    private fun onSocketOpen() {
        reconnectChecks = 0
        state.openPeer?.let { send(ClientFrame.OpenChat(it)) }
        sendWatch()
        refreshTab(state)
    }

    /** Only what a visible pane needs is computed on the phone. */
    private fun sendWatch() {
        val s = state
        val sections = if (s.tab == Tab.SETTINGS) s.settingsTab.sections else emptyList()
        socket.send(ClientFrame.Watch(sections))
    }

    private fun refreshTab(s: AppState) {
        when (s.tab) {
            Tab.CALLS -> socket.send(ClientFrame.RequestCallLogs(null, 200))
            Tab.CONTACTS -> s.selectedContact?.let { socket.send(ClientFrame.RequestContactDetail(it)) }
            Tab.CHATS -> if (s.isInfoOpen) s.openPeer?.let { requestChatExtras(it) }
            Tab.SETTINGS -> Unit
        }
    }

    private fun requestChatExtras(peer: String) {
        socket.send(ClientFrame.RequestContactDetail(peer))
        socket.send(ClientFrame.RequestChatMedia(peer))
        socket.send(ClientFrame.RequestChatLinks(peer))
    }

    private fun onSocketStatus(status: SocketStatus) {
        when (status) {
            SocketStatus.CONNECTED -> store.update { it.copy(connection = Connection.CONNECTED) }
            SocketStatus.CONNECTING -> store.update { it.copy(connection = Connection.CONNECTING) }
            SocketStatus.RECONNECTING -> {
                store.update { it.copy(connection = Connection.RECONNECTING) }
                // a refused upgrade looks like any other close; after a few, ask the phone whether we are still logged in
                reconnectChecks += 1
                if (reconnectChecks % 3 == 0) recheckSession()
            }
            SocketStatus.UNAUTHORIZED -> toLogin(null)
            SocketStatus.LIMIT -> {
                store.update { it.copy(connection = Connection.RECONNECTING) }
                toast(DexNoticeLevel.WARNING, "Client limit reached on the phone. Retrying…")
            }
        }
    }

    private fun recheckSession() {
        scope.launch {
            when (val s = Api.session()) {
                SessionResult.None -> toLogin(null)
                is SessionResult.Active -> Unit
                is SessionResult.Failed -> Unit
            }
        }
    }

    private fun toLogin(error: String?) {
        socket.stop()
        media.close()
        calls.clearVideo()
        recorder.cancel()
        store.update { AppState(screen = Screen.Login(error = error)) }
    }

    private fun send(frame: ClientFrame): Boolean {
        val ok = socket.send(frame)
        if (!ok && frame !is ClientFrame.Ping && frame !is ClientFrame.Typing) toast(DexNoticeLevel.WARNING, "Not connected to the phone right now.")
        return ok
    }

    private fun onFrame(frame: ServerFrame) {
        when (frame) {
            ServerFrame.Pong -> Unit
            is ServerFrame.Hello -> {
                store.update { it.copy(me = frame.me, clientId = frame.clientId, freeBytes = frame.freeBytes, screen = Screen.App) }
                loadEmoji()
            }
            is ServerFrame.Chats -> {
                notifyNewMessages(state.chats, frame.items)
                store.update { it.copy(chats = frame.items) }
            }
            is ServerFrame.Contacts -> store.update { it.copy(contacts = frame.items.associateBy { c -> c.ip }) }
            is ServerFrame.Chat -> store.update { s ->
                if (s.openPeer != frame.view.peer) return@update s
                val previous = s.view?.takeIf { v -> v.peer == frame.view.peer }
                val firstTs = frame.view.messages.firstOrNull()?.ts
                // a snapshot is the latest page; pages the browser scrolled back for stay in front of it
                val ids = frame.view.messages.mapTo(HashSet()) { m -> m.id }
                val older = previous?.messages?.filter { m -> firstTs != null && m.ts < firstTs && m.id !in ids }.orEmpty()
                val view = if (older.isEmpty()) frame.view else frame.view.copy(messages = older + frame.view.messages, hasMore = previous?.hasMore ?: frame.view.hasMore)
                s.copy(view = view, isLoadingMore = false)
            }
            is ServerFrame.ChatMore -> store.update { s ->
                val view = s.view
                if (view == null || view.peer != frame.peer) return@update s.copy(isLoadingMore = false)
                val known = view.messages.mapTo(HashSet()) { it.id }
                val merged = frame.messages.filter { it.id !in known } + view.messages
                s.copy(view = view.copy(messages = merged, hasMore = frame.hasMore), isLoadingMore = false)
            }
            is ServerFrame.Presence -> store.update { it.copy(presence = frame.items) }
            is ServerFrame.Typing -> store.update { it.copy(typing = frame.peers.toSet()) }
            is ServerFrame.Queues -> store.update { it.copy(queues = frame.items.associateBy { q -> q.peer }) }
            is ServerFrame.Notice -> toast(frame.level, frame.message)
            is ServerFrame.Error -> toast(DexNoticeLevel.ERROR, frame.message)
            is ServerFrame.CallState -> onCallState(frame)
            is ServerFrame.CallMedia -> onCallMedia(frame)
            is ServerFrame.CallSdp -> media.remoteSdp(frame.callId, frame.sdp, frame.sdpType)
            is ServerFrame.CallIce -> media.remoteIce(frame.callId, frame.candidate)
            is ServerFrame.Settings -> store.update { it.copy(settings = frame.settings) }
            is ServerFrame.Account -> store.update { it.copy(account = frame.account) }
            is ServerFrame.Network -> store.update { it.copy(network = frame.network) }
            is ServerFrame.Storage -> store.update { it.copy(storage = frame.storage) }
            is ServerFrame.Diagnostics -> store.update { it.copy(diagnostics = frame.diagnostics) }
            is ServerFrame.Updates -> store.update { it.copy(updates = frame.updates) }
            is ServerFrame.CallLogs -> store.update { it.copy(callLogs = frame.items) }
            is ServerFrame.ContactDetail -> store.update { it.copy(contactDetail = frame.detail) }
            is ServerFrame.ChatMedia -> store.update { it.copy(chatMedia = frame.items, chatMediaPeer = frame.peer) }
            is ServerFrame.ChatLinks -> store.update { it.copy(chatLinks = frame.items, chatLinksPeer = frame.peer) }
            is ServerFrame.PingResult -> store.update { it.copy(pings = it.pings + (frame.result.peer to frame.result)) }
            is ServerFrame.SearchResults -> store.update { if (it.openPeer == frame.peer) it.copy(chatSearchResults = frame.items) else it }
            is ServerFrame.Done -> toast(DexNoticeLevel.INFO, frame.message ?: doneLabel(frame.what))
            is ServerFrame.CallRelease -> {
                if (media.currentCallId == frame.callId || media.currentCallId == null) {
                    media.close()
                    calls.clearVideo()
                    calls.refreshControls()
                }
                if (frame.reason != "moved" && frame.reason != "ended" && frame.reason.isNotBlank()) toast(DexNoticeLevel.INFO, frame.reason)
            }
        }
    }

    private fun notifyNewMessages(previous: List<DexChat>, next: List<DexChat>) {
        if (previous.isEmpty() || !document.asDynamic().hidden.unsafeCast<Boolean>()) return
        if (!store.state.effective.notificationsEnabled) return
        val before = previous.associateBy { it.peer }
        for (chat in next) {
            val old = before[chat.peer]
            if (chat.lastDir != DexDirection.IN || chat.isMuted || chat.unread == 0) continue
            if (old != null && old.lastTs == chat.lastTs) continue
            val body = if (store.state.effective.notificationPreview) chat.lastBody ?: "New message" else "New message"
            notify(chat.label, body, "msg-${chat.peer}")
        }
    }

    /** A short tone built in the page; the stylesheet's origin forbids fetching an audio file. */
    private fun beep() {
        if (!store.state.effective.notificationSound) return
        try {
            val ctx: dynamic = js("new (window.AudioContext || window.webkitAudioContext)()")
            val osc: dynamic = ctx.createOscillator()
            val gain: dynamic = ctx.createGain()
            osc.type = "sine"
            osc.frequency.value = 880
            gain.gain.value = 0.04
            osc.connect(gain)
            gain.connect(ctx.destination)
            osc.start()
            window.setTimeout({ runCatching { osc.stop(); ctx.close() }; Unit }, 120)
        } catch (e: Throwable) {
            // no audio on this page; the notification itself still shows
        }
    }

    private fun notify(title: String, body: String, tag: String) {
        try {
            if (js("typeof Notification === 'undefined'").unsafeCast<Boolean>()) return
            if (js("Notification.permission") as String != "granted") return
            val n: dynamic = js("new Notification(title, { body: body, tag: tag })")
            beep()
            n.onclick = {
                window.focus()
                n.close()
                Unit
            }
        } catch (e: Throwable) {
            // notifications are a convenience
        }
    }

    private fun requestNotificationPermission() {
        try {
            if (js("typeof Notification === 'undefined'").unsafeCast<Boolean>()) return
            if (js("Notification.permission") as String == "default") (js("Notification.requestPermission()") as? Promise<dynamic>)?.catch { null }
        } catch (e: Throwable) {
            // unsupported
        }
    }

    private fun onKey(event: org.w3c.dom.events.Event) {
        val e = event as? org.w3c.dom.events.KeyboardEvent ?: return
        if (e.key == "Escape") {
            if (state.dialog != null) {
                openDialog(null)
            } else {
                store.update { it.copy(lightbox = null, menuFor = null, reactFor = null, prompt = null) }
            }
            return
        }
        val target = e.target
        val isTyping = target is HTMLElement && (target.tagName == "INPUT" || target.tagName == "TEXTAREA" || target.isContentEditable)
        if (e.key == "/" && !isTyping && state.screen is Screen.App) {
            e.preventDefault()
            focusSearch()
            return
        }
        if ((e.ctrlKey || e.metaKey) && e.key.lowercase() == "k" && state.screen is Screen.App) {
            e.preventDefault()
            if (state.tab != Tab.CHATS) openTab(Tab.CHATS)
            focusSearch()
        }
    }

    private fun focusSearch() {
        val selector = when (state.tab) {
            Tab.CHATS -> ".pane-list .search"
            Tab.CONTACTS -> ".pane-contacts .search"
            Tab.CALLS -> ".pane-calls .search"
            Tab.SETTINGS -> null
        } ?: return
        (document.querySelector(selector) as? HTMLElement)?.focus()
    }

    private fun doneLabel(what: String): String = when (what) {
        "clear_orphans" -> "Media nothing refers to was removed."
        "clear_history" -> "History cleared on the phone."
        "clear_all_history" -> "Every chat was cleared on the phone."
        "contact_add" -> "Contact added."
        "contact_delete" -> "Contact deleted."
        "check_updates" -> "Checked for updates."
        "retry_failed" -> "Retrying what failed."
        else -> "Done."
    }

    private fun updateTitle(s: AppState) {
        val n = s.unreadTotal
        document.title = if (n > 0) "($n) TeleNebula Dex" else "TeleNebula Dex"
    }

    // --- calls ----------------------------------------------------------------------------------

    private fun onCallState(frame: ServerFrame.CallState) {
        val previous = state.call
        val next = frame.state
        store.update { it.copy(call = next) }
        if (next.phase == DexCallPhase.INCOMING && previous.callId != next.callId && document.asDynamic().hidden.unsafeCast<Boolean>()) {
            notify(next.peer?.name ?: "Call", if (next.video) "Incoming video call" else "Incoming call", "call")
        }
        if (next.phase == DexCallPhase.ENDED || next.phase == DexCallPhase.IDLE) {
            if (media.isActive) {
                media.close()
                calls.clearVideo()
                calls.refreshControls()
            }
            if (next.phase == DexCallPhase.ENDED && previous.phase != DexCallPhase.ENDED) {
                val reason = next.endedReason
                if (!reason.isNullOrBlank()) toast(DexNoticeLevel.INFO, reason)
            }
        }
        val mine = next.seatClientId == state.clientId && next.seat == com.telenebula.web.wire.DexSeat.DEX
        if (media.isActive && media.currentCallId != next.callId && next.callId != null) {
            media.close()
            calls.clearVideo()
        }
        if (media.isActive && !mine && next.movingTo == null && next.phase == DexCallPhase.ACTIVE) {
            // the seat moved away without a release frame reaching us
            media.close()
            calls.clearVideo()
            calls.refreshControls()
        }
    }

    private fun onCallMedia(frame: ServerFrame.CallMedia) {
        val current = state.call
        if (current.callId != frame.callId) {
            // the state frame may still be in flight; accept only when nothing else is live
            if (current.callId != null && current.phase != DexCallPhase.IDLE && current.phase != DexCallPhase.ENDED) return
        }
        if (!CallMedia.isSupported) {
            toast(DexNoticeLevel.ERROR, "Calls need a secure origin and a modern browser")
            send(ClientFrame.CallFailed(frame.callId, "Calls need a secure origin and a modern browser"))
            return
        }
        media.open(frame.callId, frame.role == ServerFrame.ROLE_OFFERER, frame.video, frame.iceServers, frame.remoteSdp, frame.remoteSdpType, frame.isRestart)
        calls.refreshControls()
    }

    override fun onSdp(callId: String, sdp: String, sdpType: String) {
        send(ClientFrame.CallSdp(callId, sdp, sdpType))
    }

    override fun onIce(callId: String, candidate: DexIceCandidate?) {
        socket.send(ClientFrame.CallIce(callId, candidate))
    }

    override fun onConnected(callId: String) {
        send(ClientFrame.CallConnected(callId))
    }

    override fun onFailed(callId: String, reason: String) {
        send(ClientFrame.CallFailed(callId, reason))
        toast(DexNoticeLevel.ERROR, reason)
        calls.clearVideo()
        calls.refreshControls()
    }

    override fun onLocalStream(stream: dynamic) = calls.showLocal(stream)

    override fun onRemoteStream(stream: dynamic) = calls.showRemote(stream)

    override val isMuted: Boolean get() = media.isMuted
    override val isCameraOn: Boolean get() = media.isCameraOn

    override fun startCall(video: Boolean) {
        val peer = state.openPeer ?: return
        val phase = state.call.phase
        if (phase != DexCallPhase.IDLE && phase != DexCallPhase.ENDED) {
            toast(DexNoticeLevel.WARNING, "A call is already in progress.")
            return
        }
        if (!CallMedia.isSupported) {
            toast(DexNoticeLevel.ERROR, "Calls need a secure origin and a modern browser")
            return
        }
        requestNotificationPermission()
        send(ClientFrame.CallStart(peer, video))
    }

    override fun acceptCall() {
        val id = state.call.callId ?: return
        if (!CallMedia.isSupported) {
            toast(DexNoticeLevel.ERROR, "Calls need a secure origin and a modern browser")
            return
        }
        send(ClientFrame.CallAccept(id))
    }

    override fun rejectCall() {
        val id = state.call.callId ?: return
        send(ClientFrame.CallReject(id))
    }

    override fun endCall() {
        val id = state.call.callId ?: return
        send(ClientFrame.CallEnd(id))
    }

    override fun toggleMute() {
        media.setMuted(!media.isMuted)
        calls.refreshControls()
    }

    override fun toggleCamera() {
        val id = state.call.callId ?: return
        val on = !media.isCameraOn
        media.setCamera(on) { ok ->
            if (ok) send(ClientFrame.CallCam(id, on)) else toast(DexNoticeLevel.WARNING, "Camera is not available.")
            calls.refreshControls()
        }
    }

    override fun moveCallToPhone() {
        val c = state.call
        val id = c.callId ?: return
        if (!state.isMySeat || c.phase != DexCallPhase.ACTIVE || c.movingTo != null) return
        send(ClientFrame.CallMoveToPhone(id))
    }

    // --- session --------------------------------------------------------------------------------

    override fun login(username: String, password: String) {
        if (username.isEmpty() || password.isEmpty()) {
            store.update { it.copy(screen = Screen.Login(error = "Enter the username and password from your phone.")) }
            return
        }
        store.update { it.copy(screen = Screen.Login(isBusy = true)) }
        scope.launch {
            when (val r = Api.login(username, password)) {
                LoginResult.Ok -> {
                    requestNotificationPermission()
                    store.update { AppState(screen = Screen.App) }
                    socket.start()
                }
                LoginResult.Wrong -> store.update { it.copy(screen = Screen.Login(error = "Wrong username or password")) }
                LoginResult.Locked -> store.update { it.copy(screen = Screen.Login(error = "Too many attempts, wait a minute")) }
                LoginResult.ClientLimit -> store.update { it.copy(screen = Screen.Login(error = "Client limit reached on the phone")) }
                is LoginResult.Failed -> store.update { it.copy(screen = Screen.Login(error = r.message)) }
            }
        }
    }

    override fun logout() {
        scope.launch {
            Api.logout()
            toLogin(null)
        }
    }

    // --- chats ----------------------------------------------------------------------------------

    override fun openChat(peer: String) {
        val previous = state.openPeer
        if (previous == peer) return
        if (previous != null) {
            socket.send(ClientFrame.CloseChat(previous))
            if (state.recording != null) discardRecording()
        }
        store.update { it.copy(openPeer = peer, view = null, composer = Composer(), menuFor = null, reactFor = null, isLoadingMore = false, tab = Tab.CHATS, chatSearchResults = null, chatMedia = emptyList(), chatMediaPeer = null, chatLinks = emptyList(), chatLinksPeer = null) }
        send(ClientFrame.OpenChat(peer))
        if (state.isInfoOpen) requestChatExtras(peer)
    }

    override fun closeChat() {
        val peer = state.openPeer ?: return
        socket.send(ClientFrame.CloseChat(peer))
        if (state.recording != null) discardRecording()
        store.update { it.copy(openPeer = null, view = null, composer = Composer(), menuFor = null, reactFor = null) }
    }

    override fun setSearch(text: String) = store.update { it.copy(search = text) }

    override fun toggleArchived() = store.update { it.copy(showArchived = !it.showArchived) }

    override fun loadMore() {
        val view = state.view ?: return
        if (!view.hasMore || state.isLoadingMore) return
        val oldest = view.messages.firstOrNull() ?: return
        store.update { it.copy(isLoadingMore = true) }
        send(ClientFrame.LoadMore(view.peer, oldest.ts, oldest.id))
    }

    override fun sendText(text: String): Boolean {
        val peer = state.openPeer ?: return false
        val body = text.trim()
        if (body.isEmpty()) return false
        if (body.length > MAX_BODY) {
            toast(DexNoticeLevel.WARNING, "That message is too long.")
            return false
        }
        val c = state.composer
        val editing = c.editing
        val ok = if (editing != null) {
            if (editing.body == body) true else send(ClientFrame.Edit(editing.id, body))
        } else {
            send(ClientFrame.SendText(peer, body, c.replyTo?.id, c.isCovered))
        }
        if (ok) store.update { it.copy(composer = it.composer.copy(replyTo = null, editing = null, isEmojiOpen = false)) }
        return ok
    }

    override fun typing(isTyping: Boolean) {
        val peer = state.openPeer ?: return
        socket.send(ClientFrame.Typing(peer, isTyping))
    }

    override fun markRead() {
        val view = state.view ?: return
        if (document.asDynamic().hidden.unsafeCast<Boolean>()) return
        if (view.messages.none { it.dir == DexDirection.IN && !it.isRead }) return
        val now = Date.now().toLong()
        if (lastMarkRead.first == view.peer && now - lastMarkRead.second < 1_000) return
        lastMarkRead = view.peer to now
        socket.send(ClientFrame.MarkRead(view.peer))
    }

    override fun reply(msg: DexMessage?) = store.update { it.copy(composer = it.composer.copy(replyTo = msg, editing = null), menuFor = null, reactFor = null) }

    override fun toggleCover() = store.update { it.copy(composer = it.composer.copy(isCovered = !it.composer.isCovered)) }

    override fun startEdit(msg: DexMessage?) = store.update { it.copy(composer = it.composer.copy(editing = msg, replyTo = null), menuFor = null) }

    override fun react(msg: DexMessage, emoji: String) = reactById(msg.id, emoji)

    override fun reactById(messageId: String, emoji: String) {
        store.update { it.copy(reactFor = null) }
        send(ClientFrame.React(messageId, emoji.take(16)))
    }

    override fun deleteMessage(msg: DexMessage, forEveryone: Boolean) {
        store.update {
            it.copy(
                menuFor = null,
                prompt = Prompt(
                    if (forEveryone) "Delete this message for everyone?" else "Delete this message on your phone?",
                    "Delete",
                ) { send(ClientFrame.Delete(msg.id, forEveryone)) },
            )
        }
    }

    override fun copyMessage(msg: DexMessage) {
        try {
            val write = window.navigator.asDynamic().clipboard.writeText(msg.body).unsafeCast<Promise<Unit>>()
            write.then<Unit>({ toast(DexNoticeLevel.INFO, "Copied") }, { toast(DexNoticeLevel.WARNING, "Couldn't copy.") })
        } catch (e: Throwable) {
            toast(DexNoticeLevel.WARNING, "Couldn't copy.")
        }
    }

    override fun retrySend(msg: DexMessage) {
        val id = msg.actionId ?: return toast(DexNoticeLevel.WARNING, "Nothing to retry.")
        send(ClientFrame.RetryAction(id))
    }

    override fun cancelSend(msg: DexMessage) {
        val id = msg.actionId ?: return toast(DexNoticeLevel.WARNING, "Nothing to cancel.")
        send(ClientFrame.CancelAction(id))
    }

    override fun acceptOffer(msg: DexMessage) {
        send(ClientFrame.AcceptOffer(msg.id))
    }

    override fun declineOffer(msg: DexMessage) {
        send(ClientFrame.DeclineOffer(msg.id))
    }

    override fun cancelTransfer(msg: DexMessage) {
        send(ClientFrame.CancelTransfer(msg.id))
    }

    override fun reveal(msg: DexMessage) {
        val gate = state.view?.contact?.revealGate ?: state.contacts[msg.peer]?.revealGate ?: DexRevealGate.TAP
        when (gate) {
            DexRevealGate.TAP -> store.update { it.copy(revealed = it.revealed + msg.id) }
            DexRevealGate.ASK -> store.update {
                it.copy(prompt = Prompt("Reveal this message? It stays open until you leave this chat.", "Reveal") { store.update { s -> s.copy(revealed = s.revealed + msg.id) } })
            }
            DexRevealGate.CODE, DexRevealGate.DEVICE -> toast(DexNoticeLevel.INFO, "Reveal this message on your phone")
        }
    }

    override fun openLightbox(id: String?) = store.update { it.copy(lightbox = id) }

    override fun openMenu(id: String?) = store.update { it.copy(menuFor = id, reactFor = null) }

    override fun openReactions(id: String?) = store.update { it.copy(reactFor = id, menuFor = null) }

    override fun toggleEmoji() = store.update { it.copy(composer = it.composer.copy(isEmojiOpen = !it.composer.isEmojiOpen)) }

    override fun scrolledToBottom() {
        if (state.hasNewBelow) store.update { it.copy(hasNewBelow = false) }
    }

    override fun confirmPrompt() {
        val p = state.prompt ?: return
        store.update { it.copy(prompt = null) }
        p.onConfirm()
    }

    override fun dismissPrompt() = store.update { it.copy(prompt = null) }

    override fun dismissToast(id: Int) = store.update { it.copy(toasts = it.toasts.filter { t -> t.id != id }) }

    private fun toast(level: DexNoticeLevel, message: String) {
        val id = nextId++
        store.update { it.copy(toasts = (it.toasts + Toast(id, level, message)).takeLast(4)) }
        window.setTimeout({ dismissToast(id) }, if (level == DexNoticeLevel.ERROR) 8_000 else 4_500)
    }

    // --- uploads --------------------------------------------------------------------------------

    override fun attach(files: List<File>) {
        val peer = state.openPeer ?: return
        if (files.isEmpty()) return
        if (uploadCount + files.size > MAX_PARALLEL_UPLOADS) {
            toast(DexNoticeLevel.WARNING, "Wait for the current uploads to finish.")
            return
        }
        val c = state.composer
        val replyTo = c.replyTo?.id
        store.update { it.copy(composer = it.composer.copy(replyTo = null)) }
        for (file in files) upload(peer, file, replyTo, c.isCovered)
    }

    private fun upload(peer: String, file: File, replyTo: String?, isCovered: Boolean) {
        val id = nextId++
        uploadCount += 1
        store.update { it.copy(uploads = it.uploads + Upload(id, file.name, 0)) }
        scope.launch {
            val mime = file.type.ifBlank { "application/octet-stream" }
            val size = if (mime.startsWith("image/")) imageSize(file) else null
            val request = UploadRequest(peer, file.name, mime, file, replyTo, isCovered, isVoice = false, durationMs = null, width = size?.first, height = size?.second)
            finishUpload(id, Api.upload(request, { h -> uploadHandles[id] = h }) { pct -> setUploadPct(id, pct) })
        }
    }

    private fun finishUpload(id: Int, outcome: UploadOutcome) {
        uploadHandles.remove(id)
        uploadCount = (uploadCount - 1).coerceAtLeast(0)
        when (outcome) {
            UploadOutcome.Done, UploadOutcome.Cancelled -> store.update { it.copy(uploads = it.uploads.filter { u -> u.id != id }) }
            is UploadOutcome.Failed -> store.update { it.copy(uploads = it.uploads.map { u -> if (u.id == id) u.copy(error = outcome.message) else u }) }
        }
    }

    private fun setUploadPct(id: Int, pct: Int) = store.update { it.copy(uploads = it.uploads.map { u -> if (u.id == id && u.pct != pct) u.copy(pct = pct) else u }) }

    private suspend fun imageSize(file: File): Pair<Int, Int>? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        fun settle(value: Pair<Int, Int>?) {
            if (cont.isActive) cont.resume(value)
        }
        try {
            val url = URL.createObjectURL(file)
            val img: dynamic = js("new Image()")
            img.onload = {
                URL.revokeObjectURL(url)
                settle(Pair((img.naturalWidth as Number).toInt(), (img.naturalHeight as Number).toInt()))
                Unit
            }
            img.onerror = {
                URL.revokeObjectURL(url)
                settle(null)
                Unit
            }
            img.src = url
        } catch (e: Throwable) {
            settle(null)
        }
    }

    override fun cancelUpload(id: Int) {
        uploadHandles[id]?.abort() ?: store.update { it.copy(uploads = it.uploads.filter { u -> u.id != id }) }
    }

    override fun dismissUpload(id: Int) = store.update { it.copy(uploads = it.uploads.filter { u -> u.id != id }) }

    // --- voice ----------------------------------------------------------------------------------

    override fun startRecording() {
        if (state.openPeer == null || state.recording != null) return
        if (!Recorder.isSupported) {
            toast(DexNoticeLevel.WARNING, "Voice messages need a secure origin and a modern browser.")
            return
        }
        scope.launch {
            if (recorder.start()) {
                store.update { it.copy(recording = Recording.Live(Date.now().toLong())) }
            } else {
                toast(DexNoticeLevel.WARNING, "Microphone is not available.")
            }
        }
    }

    override fun stopRecording() {
        if (state.recording !is Recording.Live) return
        recorder.stop { clip ->
            if (clip == null) {
                store.update { it.copy(recording = null) }
                toast(DexNoticeLevel.WARNING, "Nothing was recorded.")
            } else {
                store.update { it.copy(recording = Recording.Stopped(clip.blob, clip.mime, clip.durationMs, URL.createObjectURL(clip.blob))) }
            }
        }
    }

    override fun discardRecording() {
        val r = state.recording
        recorder.cancel()
        if (r is Recording.Stopped) URL.revokeObjectURL(r.url)
        store.update { it.copy(recording = null) }
    }

    override fun sendRecording() {
        val peer = state.openPeer ?: return
        val r = state.recording
        if (r is Recording.Live) {
            recorder.stop { clip ->
                if (clip == null) {
                    store.update { it.copy(recording = null) }
                    toast(DexNoticeLevel.WARNING, "Nothing was recorded.")
                } else {
                    uploadClip(peer, Recording.Stopped(clip.blob, clip.mime, clip.durationMs, URL.createObjectURL(clip.blob)))
                }
            }
            return
        }
        if (r is Recording.Stopped) uploadClip(peer, r)
    }

    private fun uploadClip(peer: String, r: Recording.Stopped) {
        val c = state.composer
        store.update { it.copy(recording = null, composer = it.composer.copy(replyTo = null)) }
        URL.revokeObjectURL(r.url)
        val id = nextId++
        uploadCount += 1
        val name = "Voice message.${Recorder.extensionFor(r.mime)}"
        store.update { it.copy(uploads = it.uploads + Upload(id, name, 0)) }
        scope.launch {
            val request = UploadRequest(peer, name, r.mime, r.blob, c.replyTo?.id, c.isCovered, isVoice = true, durationMs = r.durationMs, width = null, height = null)
            finishUpload(id, Api.upload(request, { h -> uploadHandles[id] = h }) { pct -> setUploadPct(id, pct) })
        }
    }


    // --- shell ----------------------------------------------------------------------------------

    override fun openTab(tab: Tab) {
        if (state.tab == tab) return
        store.update { it.copy(tab = tab, isRailOpen = false, callSelection = emptySet()) }
        sendWatch()
        refreshTab(state)
    }

    override fun toggleRail() = store.update { it.copy(isRailOpen = !it.isRailOpen) }

    override fun openSettingsTab(tab: SettingsTab) {
        if (state.settingsTab == tab) return
        store.update { it.copy(settingsTab = tab) }
        sendWatch()
    }

    override fun openDialog(dialog: Dialog?) = store.update { it.copy(dialog = dialog) }

    override fun updateDialog(dialog: Dialog) = store.update { it.copy(dialog = dialog) }

    override fun submitDialog() {
        when (val d = state.dialog) {
            is Dialog.AddContact -> {
                val ip = d.ip.trim()
                if (ip.isEmpty()) {
                    store.update { it.copy(dialog = d.copy(error = "Enter their overlay address.")) }
                    return
                }
                send(ClientFrame.ContactAdd(ip, d.name.trim().ifEmpty { ip }, d.nickname.trim(), d.notes.trim()))
                store.update { it.copy(dialog = null, selectedContact = ip) }
            }
            else -> store.update { it.copy(dialog = null) }
        }
    }

    // --- settings -------------------------------------------------------------------------------

    override fun patchSettings(patch: DexSettingsPatch) {
        send(ClientFrame.SetSettings(patch))
    }

    override fun setQuickReaction(slot: Int, emoji: String) {
        send(ClientFrame.SetQuickReaction(slot, emoji.take(16)))
    }

    override fun setTunnel(isOn: Boolean) {
        send(ClientFrame.SetTunnel(isOn))
    }

    override fun clearOrphans() {
        send(ClientFrame.ClearOrphans)
    }

    override fun clearAllHistory() {
        store.update {
            it.copy(prompt = Prompt("Clear every message in every chat on the phone? This cannot be undone.", "Clear") { send(ClientFrame.ClearAllHistory) })
        }
    }

    override fun checkUpdates() {
        send(ClientFrame.CheckUpdates)
    }

    override fun retryFailed(peer: String) {
        send(ClientFrame.RetryFailed(peer))
    }

    override fun drain(peer: String) {
        send(ClientFrame.Drain(peer))
    }

    // --- contacts -------------------------------------------------------------------------------

    override fun setContactSearch(text: String) = store.update { it.copy(contactSearch = text) }

    override fun selectContact(peer: String?) {
        store.update { it.copy(selectedContact = peer, contactDetail = if (peer == null) null else it.contactDetail?.takeIf { d -> d.contact.ip == peer }) }
        if (peer != null) send(ClientFrame.RequestContactDetail(peer))
    }

    override fun saveContact(peer: String, name: String, nickname: String, notes: String) {
        send(ClientFrame.ContactSave(peer, name, nickname, notes))
    }

    override fun addContact(ip: String, name: String, nickname: String, notes: String) {
        send(ClientFrame.ContactAdd(ip, name, nickname, notes))
    }

    override fun deleteContact(peer: String) {
        val label = state.contacts[peer]?.label ?: peer
        store.update {
            it.copy(
                prompt = Prompt("Delete $label from the phone? The conversation stays.", "Delete") {
                    send(ClientFrame.ContactDelete(peer))
                    store.update { s -> s.copy(selectedContact = null, contactDetail = null) }
                },
            )
        }
    }

    override fun setContactFlags(peer: String, flags: DexContactFlags) {
        send(ClientFrame.ContactFlagsSet(peer, flags))
    }

    override fun setContactPrivacy(peer: String, privacy: DexContactPrivacy) {
        send(ClientFrame.ContactPrivacySet(peer, privacy))
    }

    override fun setContactNotifications(peer: String, prefs: DexContactNotifications?) {
        send(ClientFrame.ContactNotificationsSet(peer, prefs))
    }

    override fun changeContactIp(peer: String, newIp: String) {
        val ip = newIp.trim()
        if (ip.isEmpty() || ip == peer) {
            store.update { it.copy(dialog = (it.dialog as? Dialog.ChangeIp)?.copy(error = "Enter a different overlay address.")) }
            return
        }
        send(ClientFrame.ContactChangeIp(peer, ip))
        store.update { it.copy(dialog = null, selectedContact = ip) }
    }

    override fun clearHistory(peer: String) {
        val label = state.contacts[peer]?.label ?: peer
        store.update {
            it.copy(prompt = Prompt("Clear every message with $label? This cannot be undone.", "Clear") { send(ClientFrame.ClearHistory(peer)) })
        }
    }

    override fun pingPeer(peer: String) {
        send(ClientFrame.PingPeer(peer))
    }

    // --- calls ----------------------------------------------------------------------------------

    override fun setCallFilter(filter: CallFilter) = store.update { it.copy(callFilter = filter) }

    override fun setCallSearch(text: String) = store.update { it.copy(callSearch = text) }

    override fun toggleCallSelected(id: String) = store.update {
        it.copy(callSelection = if (id in it.callSelection) it.callSelection - id else it.callSelection + id)
    }

    override fun clearCallSelection() = store.update { it.copy(callSelection = emptySet()) }

    override fun deleteSelectedCalls() {
        val ids = state.callSelection.toList()
        if (ids.isEmpty()) return
        store.update {
            it.copy(
                prompt = Prompt(if (ids.size == 1) "Delete this call from the log?" else "Delete ${ids.size} calls from the log?", "Delete") {
                    send(ClientFrame.DeleteCallLogs(ids))
                    store.update { s -> s.copy(callSelection = emptySet()) }
                },
            )
        }
    }

    override fun callPeer(peer: String, video: Boolean) {
        val phase = state.call.phase
        if (phase != DexCallPhase.IDLE && phase != DexCallPhase.ENDED) {
            toast(DexNoticeLevel.WARNING, "A call is already in progress.")
            return
        }
        if (!CallMedia.isSupported) {
            toast(DexNoticeLevel.ERROR, "Calls need a secure origin and a modern browser")
            return
        }
        requestNotificationPermission()
        send(ClientFrame.CallStart(peer, video))
    }

    // --- chat info ------------------------------------------------------------------------------

    override fun toggleInfo() {
        val open = !state.isInfoOpen
        store.update { it.copy(isInfoOpen = open) }
        if (open) state.openPeer?.let { requestChatExtras(it) }
    }

    override fun searchChat(text: String) {
        val peer = state.openPeer ?: return
        val query = text.trim()
        if (query.isEmpty()) {
            store.update { it.copy(chatSearchResults = null) }
            return
        }
        send(ClientFrame.Search(peer, query))
    }

    private companion object {
        const val MAX_BODY = 16 * 1024
        const val MAX_PARALLEL_UPLOADS = 4
    }
}

package com.telenebula.app.ui.screens.chat

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.Manifest
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.telenebula.app.sheets.MediaViewerCenter
import com.telenebula.app.sheets.SheetCenter
import com.telenebula.app.sheets.SheetRequest
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.model.AttachmentKind
import com.telenebula.app.model.PickResult
import com.telenebula.app.nav.ChatSettings
import com.telenebula.app.nav.Contact as ContactKey
import com.telenebula.app.nav.Navigator
import com.telenebula.app.nav.Tab
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.notices.Prompt
import com.telenebula.app.platform.ActivityGateway
import com.telenebula.app.platform.AttachmentStore
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.platform.Format
import com.telenebula.app.platform.OpenWith
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.platform.VoicePlayback
import com.telenebula.app.platform.VoicePlayer
import com.telenebula.app.platform.VoiceRecorder
import com.telenebula.app.platform.deniedCallPermission
import com.telenebula.app.platform.userMessage
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.app.ui.fragments.ForwardTarget
import com.telenebula.app.ui.fragments.MediaSource
import com.telenebula.app.ui.fragments.MediaViewerTarget
import com.telenebula.app.ui.fragments.MessageMenuState
import com.telenebula.app.ui.fragments.ReplyPreview
import com.telenebula.app.ui.fragments.TimelineItem
import com.telenebula.app.ui.fragments.mediaSource
import com.telenebula.app.ui.shared.ChatSearchRequests
import com.telenebula.calls.CallEngine
import com.telenebula.core.CoreClient
import com.telenebula.core.debounceAfterFirst
import com.telenebula.core.PeerQueueStore
import com.telenebula.core.TransferProgressStore
import com.telenebula.core.PresenceStore
import com.telenebula.core.TypingStore
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.ChatView
import com.telenebula.core.model.ChatTextSize
import com.telenebula.core.model.Contact
import com.telenebula.core.model.ContactFlagsPatch
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import com.telenebula.core.model.MessageAttachment
import com.telenebula.core.model.MessageDensity
import com.telenebula.core.model.MessageCursor
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus
import com.telenebula.core.model.PeerQueueState
import com.telenebula.core.model.PeerPresence
import com.telenebula.core.model.Prefs
import com.telenebula.app.ui.shared.uiState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PingResult { ONLINE, REACHABLE, OFFLINE }

/** What the composer is doing; editing and replying exclude each other by construction. */
sealed interface ComposerMode {
    data object Idle : ComposerMode
    class Editing(val message: ChatMessage) : ComposerMode
    class Replying(val to: ChatMessage) : ComposerMode
}

/** The one thing drawn over the chat, if any. */
sealed interface ChatOverlay {
    data object None : ChatOverlay
    class Menu(val menu: MessageMenuState) : ChatOverlay
    data object Attach : ChatOverlay
    class Forward(val contacts: List<ForwardTarget>) : ChatOverlay
}

data class ChatUiState(
    val peerIp: String,
    /** false only until the chat has been read once: no "no messages yet" hint before that */
    val isLoaded: Boolean = false,
    val peerName: String = "",
    val peerSubtitle: String = "",
    val isTunnelOn: Boolean = false,
    /** the peer answered its last probe: what is queued is moving rather than waiting for it */
    val isPeerReachable: Boolean = true,
    /** actions still waiting to reach this peer */
    val queuedCount: Int = 0,
    val isPinging: Boolean = false,
    val pingResult: PingResult? = null,
    val isBlocked: Boolean = false,
    val isArchived: Boolean = false,
    val isPeerTyping: Boolean = false,
    val isPeerOnline: Boolean = false,
    val isDisappearing: Boolean = false,
    val disappearLabel: String = "Off",
    /** newest first, for `LazyColumn(reverseLayout = true)` */
    val items: List<TimelineItem> = emptyList(),
    /** the oldest row on screen, where the next older page starts */
    val oldestCursor: MessageCursor? = null,
    val canLoadOlder: Boolean = false,
    val isLoadingOlder: Boolean = false,
    /** the window is full of history, so new messages are not on screen until "jump to latest" */
    val isDetachedFromLatest: Boolean = false,
    val isRecording: Boolean = false,
    val recordingLabel: String = "",
    /** the clip playing or paused anywhere in the app; a bubble shows it only for its own message */
    val voicePlayback: VoicePlayback? = null,
    val actionsByMessage: Map<String, List<MessageAction>> = emptyMap(),
    val replyPreviews: Map<String, ReplyPreview> = emptyMap(),
    /** message id -> character ranges of the URLs in its body; absent when it has none */
    val linkRanges: Map<String, List<IntRange>> = emptyMap(),
    /** room left for attachments, so an offer too big to fit says so before it is accepted */
    val freeBytes: Long = 0,
    val transferProgress: Map<String, Double> = emptyMap(),
    val seenAvatarMessageId: String? = null,
    val expandedMessageId: String? = null,
    val textSizeSp: Float = 15.5f,
    val isCompact: Boolean = false,
    val isEnterToSend: Boolean = false,
    val isTypingIndicatorOn: Boolean = true,
    val quickReactions: List<String> = emptyList(),
    val draft: String = "",
    val canSend: Boolean = false,
    val composer: ComposerMode = ComposerMode.Idle,
    val isSearching: Boolean = false,
    val searchQuery: String = "",
    val overlay: ChatOverlay = ChatOverlay.None,
    val error: String? = null,
)

@Stable
interface ChatActions {
    fun acceptOffer(msg: ChatMessage)
    fun attachFile()
    fun attachMedia()
    fun startVoiceMessage()
    fun cancelVoiceMessage()
    fun sendVoiceMessage()
    fun toggleVoice(msg: ChatMessage)
    fun coverMessageSoon()
    fun beginEdit()
    fun beginReply(msg: ChatMessage)
    fun cancelEdit()
    fun cancelIncomingTransfer(msg: ChatMessage)
    fun cancelReply()
    fun clearError()
    fun closeAttachSheet()
    fun closeForward()
    fun closeMessageMenu()
    fun copyMessage()
    fun declineOffer(msg: ChatMessage)
    fun deleteForEveryone()
    fun deleteForMe()
    fun endSearch()
    fun forwardTo(targetIp: String)
    fun goBack(): Boolean
    fun jumpToLatest()
    fun loadOlder()
    fun reachedNewest()
    fun openActionsSheet()
    fun openAttachment(msg: ChatMessage)
    fun openAttachSheet()
    fun openChatSettings()
    fun openContact()
    fun openForward()
    fun openLink(url: String)
    fun openMessageMenu(msg: ChatMessage)
    fun openReactionPicker()
    fun openReactions(msg: ChatMessage)
    fun openSettings()
    fun pingPeer()
    fun reactWith(emoji: String)
    fun replyFromMenu()
    fun retrySendNow(msg: ChatMessage)
    fun send()
    fun setDraft(value: String)
    fun setSearchQuery(value: String)
    fun startAudioCall()
    fun toggleMessageDetails(msg: ChatMessage)
    fun unarchive()
    fun unblock()
}

/**
 * One chat. A single core query returns messages, actions, reply sources and the contact; the
 * timeline is rebuilt only when that view changes, and every row model is a value, so Compose
 * skips rows whose data did not change. The first frame comes from the core's cached view of
 * this chat (or, for a chat never opened, the cached contact), never from a blank state.
 */
class ChatViewModel(
    private val peerIp: String,
    private val context: Context,
    private val core: CoreClient,
    private val runtime: AppRuntime,
    private val prefs: PrefsRepository,
    private val typing: TypingStore,
    private val presence: PresenceStore,
    private val transfers: TransferProgressStore,
    private val peerQueues: PeerQueueStore,
    private val chatSearch: ChatSearchRequests,
    private val gateway: ActivityGateway,
    private val files: AttachmentStore,
    private val openWith: OpenWith,
    private val viewer: MediaViewerCenter,
    private val voice: VoicePlayer,
    private val recorder: VoiceRecorder,
    private val callEngine: CallEngine,
    private val sheets: SheetCenter,
    private val notices: NoticeCenter,
    private val navigator: Navigator,
) : ViewModel(), ChatActions {
    private sealed interface LocalComposer {
        data object Idle : LocalComposer
        class Editing(val id: String) : LocalComposer
        class Replying(val id: String) : LocalComposer
    }

    private sealed interface LocalOverlay {
        data object None : LocalOverlay
        class Menu(val id: String) : LocalOverlay
        data object Attach : LocalOverlay
        class Forward(val id: String) : LocalOverlay
    }

    private data class Local(
        val draft: String = "",
        val composer: LocalComposer = LocalComposer.Idle,
        val overlay: LocalOverlay = LocalOverlay.None,
        val expandedMessageId: String? = null,
        val isSearching: Boolean = false,
        val searchQuery: String = "",
        val isPinging: Boolean = false,
        val pingResult: PingResult? = null,
        val freeBytes: Long = 0,
        val error: String? = null,
        val recording: Recording? = null,
        val recordingElapsedMs: Long = 0,
    )

    private class Recording(val file: File, val startedAtMs: Long)

    private var recordingJob: Job? = null

    /** how far back the reader has paged; null is the plain head of the chat */
    private data class Window(val anchor: MessageCursor? = null, val isExhausted: Boolean = false, val isLoading: Boolean = false)

    private val local = MutableStateFlow(Local())
    private val window = MutableStateFlow(Window())
    private val isVisible = MutableStateFlow(false)
    private var lastMarkedIncomingTs = 0L
    private var lastTypingSentAt = 0L
    private var typingIdleJob: Job? = null

    private var pingResultJob: Job? = null

    private val headView = core.chatViewState(peerIp)
    private val chatView: Flow<ChatView?> = window.map { it.anchor }.distinctUntilChanged().flatMapLatest { anchor ->
        if (anchor == null) headView else core.chatWindowFlow(peerIp, anchor, WINDOW_MAX)
    }
    private val projection = ChatProjection(peerIp, core::cachedContact)

    /** Pinned from the first loaded view: the chat marks itself read on screen, and the divider must not jump. */
    private val unreadBoundaryId: StateFlow<String?> = headView.filterNotNull().map(ChatProjection::firstUnreadId).take(1)
        .stateIn(viewModelScope, SharingStarted.Eagerly, headView.value?.let(ChatProjection::firstUnreadId))
    private val derived = combine(chatView, unreadBoundaryId, projection::of)

    private val searchResults = local.map { it.isSearching to it.searchQuery }.distinctUntilChanged().debounceAfterFirst(200)
        .flatMapLatest { (searching, q) ->
            if (searching && q.isNotBlank()) flowOf(Unit).mapLatest { core.searchMessages(peerIp, q).asReversed().map { TimelineItem.Msg(it) } } else flowOf(null)
        }

    private val forwardContacts = core.contacts.map(::forwardTargets)

    private data class Content(val derived: ChatDerived, val search: List<TimelineItem>?, val forward: List<ForwardTarget>, val presence: PresenceStore.Entry?, val window: Window, val voice: VoicePlayback?)

    private val windowAndVoice = combine(window, voice.state) { w, v -> w to v }

    val uiState: StateFlow<ChatUiState> = combine(
        combine(derived, searchResults, forwardContacts, presence.presence.map { it[peerIp] }.distinctUntilChanged(), windowAndVoice) { d, s, f, p, (w, v) -> Content(d, s, f, p, w, v) },
        combine(runtime.tunnelRunning, prefs.prefs, typing.typing, transfers.progress, peerQueues.queues) { running, p, t, tr, q ->
            Quint(running, p, t, tr, q[peerIp])
        },
        local,
    ) { (d, search, forward, peerPresence, w, playing), (running, p, typingSet, progress, queue), l ->
        build(d, search, forward, peerPresence, w, playing, running, p, typingSet, progress, queue, l)
    }.uiState(
        viewModelScope,
        // every input is readable now: the cached view (or contact) for the list and header, the
        // tunnel and prefs for the rest, so the first frame is the chat and not a blank shell
        build(projection.of(headView.value, unreadBoundaryId.value), null, forwardTargets(core.contacts.value), presence.of(peerIp), window.value, voice.state.value, runtime.tunnelRunning.value, prefs.prefs.value, typing.typing.value, transfers.progress.value, peerQueues.of(peerIp), local.value),
    )

    private fun forwardTargets(contacts: List<Contact>?): List<ForwardTarget> = contacts.orEmpty().map { ForwardTarget(it.ip, ContactLabels.chatLabel(it)) }

    private fun build(
        d: ChatDerived,
        search: List<TimelineItem>?,
        forward: List<ForwardTarget>,
        peerPresence: PresenceStore.Entry?,
        w: Window,
        playing: VoicePlayback?,
        running: Boolean,
        p: Prefs,
        typingSet: Set<String>,
        progress: Map<String, Double>,
        queue: PeerQueueState?,
        l: Local,
    ): ChatUiState {
        val contact = d.view.contact
        val isBlocked = contact?.isBlocked == true
        val isArchived = contact?.isArchived == true
        val now = System.currentTimeMillis()
        val presenceNow = if (running) p.presence.seenAs(PresenceStore.fresh(peerPresence, now), now) else PeerPresence.OFFLINE
        val disappear = contact?.disappearSeconds ?: 0
        val composer = when (val c = l.composer) {
            LocalComposer.Idle -> ComposerMode.Idle
            is LocalComposer.Editing -> d.byId[c.id]?.let(ComposerMode::Editing) ?: ComposerMode.Idle
            is LocalComposer.Replying -> (d.byId[c.id] ?: d.view.replySources[c.id])?.let(ComposerMode::Replying) ?: ComposerMode.Idle
        }
        val overlay = when (val o = l.overlay) {
            LocalOverlay.None -> ChatOverlay.None
            is LocalOverlay.Menu -> d.byId[o.id]?.let { ChatOverlay.Menu(menuFor(it, d.view.actions[it.id].orEmpty(), isArchived)) } ?: ChatOverlay.None
            LocalOverlay.Attach -> ChatOverlay.Attach
            is LocalOverlay.Forward -> ChatOverlay.Forward(forward)
        }
        return ChatUiState(
            peerIp = peerIp,
            isLoaded = d.isLoaded,
            peerName = contact?.let(ContactLabels::chatLabel) ?: peerIp,
            peerSubtitle = when {
                peerIp in typingSet -> "typing…"
                presenceNow == PeerPresence.ONLINE -> "online"
                presenceNow == PeerPresence.REACHABLE -> "reachable"
                presenceNow == PeerPresence.OFFLINE -> "offline"
                queue?.isReachable == false -> "unreachable"
                contact != null -> Format.lastSeen(contact.lastSeenAt)
                else -> peerIp
            },
            isTunnelOn = running,
            // no entry means nothing is queued for this peer, so nothing is waiting on it
            isPeerReachable = queue?.isReachable ?: true,
            queuedCount = queue?.queued ?: 0,
            isPinging = l.isPinging,
            // the answer says whether the peer is there; what the pong said about them refines it
            pingResult = l.pingResult?.let { if (it == PingResult.REACHABLE && presenceNow == PeerPresence.ONLINE) PingResult.ONLINE else it },
            isBlocked = isBlocked,
            isArchived = isArchived,
            isPeerTyping = peerIp in typingSet,
            isPeerOnline = presenceNow == PeerPresence.ONLINE,
            isDisappearing = disappear > 0,
            disappearLabel = if (disappear > 0) Format.seconds(disappear) else "Off",
            items = search ?: d.items,
            oldestCursor = d.view.messages.firstOrNull()?.let { MessageCursor(it.ts, it.id) },
            canLoadOlder = d.isLoaded && search == null && !w.isExhausted && !w.isLoading && d.view.messages.isNotEmpty(),
            isLoadingOlder = w.isLoading,
            isDetachedFromLatest = w.anchor != null && d.view.messages.size >= WINDOW_MAX,
            isRecording = l.recording != null,
            recordingLabel = Format.clockMs(l.recordingElapsedMs),
            voicePlayback = playing,
            actionsByMessage = d.view.actions,
            replyPreviews = d.replyPreviews,
            linkRanges = d.linkRanges,
            freeBytes = l.freeBytes,
            transferProgress = progress,
            seenAvatarMessageId = d.seenAvatarMessageId,
            expandedMessageId = l.expandedMessageId,
            textSizeSp = when (p.chatTextSize) {
                ChatTextSize.SMALL -> 14f
                ChatTextSize.MEDIUM -> 15.5f
                ChatTextSize.LARGE -> 17.5f
            },
            isCompact = p.messageDensity == MessageDensity.COMPACT,
            isEnterToSend = p.isEnterToSend,
            isTypingIndicatorOn = contact?.privacy?.sendTypingIndicators ?: p.sendTypingIndicators,
            quickReactions = p.quickReactions,
            draft = l.draft,
            canSend = l.draft.isNotBlank() && !isBlocked && !isArchived && running,
            composer = composer,
            isSearching = l.isSearching,
            searchQuery = l.searchQuery,
            overlay = overlay,
            error = l.error,
        )
    }

    private class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E) {
        operator fun component1() = a
        operator fun component2() = b
        operator fun component3() = c
        operator fun component4() = d
        operator fun component5() = e
    }

    init {
        // read once off the main thread: it only feeds a hint on the offer row, and the core
        // re-reads it for the decision that actually matters when an offer is accepted
        viewModelScope.launch {
            val free = withContext(Dispatchers.IO) { files.freeBytes() }
            local.update { it.copy(freeBytes = free) }
        }
        // every message that arrives while the chat is on screen counts as read; the newest incoming
        // timestamp guards against re-marking on our own summaries refresh
        viewModelScope.launch {
            combine(derived, isVisible) { d, visible -> if (visible && d.isLoaded) d else null }.collect { d ->
                if (d == null) return@collect
                val newestIn = d.view.messages.lastOrNull { it.direction == MessageDirection.IN }?.ts ?: 0L
                if (newestIn != lastMarkedIncomingTs || lastMarkedIncomingTs == 0L) {
                    lastMarkedIncomingTs = if (newestIn == 0L) -1L else newestIn
                    core.markChatRead(peerIp)
                }
            }
        }
        // popping back from chat settings cannot carry an argument, so search arrives as a signal
        viewModelScope.launch {
            chatSearch.peerIps.collect { if (it == peerIp) beginSearch() }
        }
        // presence is only ever learnt from a pong, so the chat on screen asks for one now and then
        viewModelScope.launch {
            combine(isVisible, runtime.tunnelRunning) { visible, running -> visible && running }.distinctUntilChanged().collectLatest { active ->
                if (!active) return@collectLatest
                while (true) {
                    if (!uiState.value.isBlocked) core.pingPeer(peerIp)
                    delay(PRESENCE_REFRESH_MS)
                }
            }
        }
    }

    private fun menuFor(msg: ChatMessage, actions: List<MessageAction>, isArchived: Boolean): MessageMenuState {
        // delete-for-everyone only once the peer actually received the message
        val isSent = msg.direction == MessageDirection.OUT && actions.any { it.type == MessageActionType.SEND && it.status == MessageActionStatus.SUCCESS }
        val myIp = runtime.profile.value?.overlayIp.orEmpty()
        // a declined file holds nothing: it was never transferred, so quoting, copying or
        // forwarding it would carry an empty message, exactly as for a deleted one
        val isContentless = msg.isDeleted || msg.status.hasNoFile
        return MessageMenuState(
            messageId = msg.id,
            canReply = !isContentless && !isArchived,
            canEdit = msg.direction == MessageDirection.OUT && !msg.isDeleted && msg.kind == MessageKind.TEXT && !isArchived,
            canCopy = !isContentless && msg.body.isNotEmpty(),
            canDeleteEveryone = isSent && !msg.isDeleted,
            canReact = !msg.isDeleted,
            canForward = !isContentless,
            myReaction = msg.reactions[myIp],
        )
    }

    private fun message(id: String?): ChatMessage? = id?.let { mid -> uiState.value.items.firstNotNullOfOrNull { (it as? TimelineItem.Msg)?.msg?.takeIf { m -> m.id == mid } } }

    // --- visibility ---

    fun onShown() {
        isVisible.value = true
    }

    fun onHidden() {
        isVisible.value = false
    }

    // --- composing ---

    override fun setDraft(value: String) {
        local.update { it.copy(draft = value) }
        val s = uiState.value
        if (value.isBlank()) {
            stopTyping()
            return
        }
        if (!s.isTypingIndicatorOn || s.isBlocked) return
        // at most one "typing" signal every 4 s; a "stopped" signal after 5 s of silence
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAt > TYPING_INTERVAL_MS) {
            lastTypingSentAt = now
            core.sendTyping(peerIp, true)
        }
        typingIdleJob?.cancel()
        typingIdleJob = viewModelScope.launch {
            delay(TYPING_IDLE_MS)
            stopTyping()
        }
    }

    private fun stopTyping() {
        typingIdleJob?.cancel()
        typingIdleJob = null
        if (lastTypingSentAt != 0L) {
            lastTypingSentAt = 0
            core.sendTyping(peerIp, false)
        }
    }

    override fun send() {
        val l = local.value
        val body = l.draft.trim()
        if (body.isEmpty()) return
        jumpToLatest()
        local.update { it.copy(draft = "", composer = LocalComposer.Idle) }
        stopTyping()
        viewModelScope.launch {
            when (val c = l.composer) {
                is LocalComposer.Editing -> core.editMessage(c.id, body)
                is LocalComposer.Replying -> core.sendText(peerIp, body, c.id)
                LocalComposer.Idle -> core.sendText(peerIp, body, null)
            }
        }
    }

    override fun cancelEdit() = local.update { it.copy(composer = LocalComposer.Idle, draft = "") }
    override fun cancelReply() = local.update { it.copy(composer = LocalComposer.Idle) }

    override fun beginReply(msg: ChatMessage) {
        if (msg.isDeleted || msg.status.hasNoFile) return
        local.update { it.copy(composer = LocalComposer.Replying(msg.id), overlay = LocalOverlay.None) }
    }

    override fun openAttachSheet() = local.update { it.copy(overlay = LocalOverlay.Attach) }
    override fun closeAttachSheet() = closeOverlay()

    private fun closeOverlay() = local.update { it.copy(overlay = LocalOverlay.None) }

    private fun menuId(): String? = (local.value.overlay as? LocalOverlay.Menu)?.id
    override fun attachMedia() = attach(AttachmentKind.MEDIA)
    override fun attachFile() = attach(AttachmentKind.DOCUMENT)

    private fun attach(kind: AttachmentKind) {
        closeOverlay()
        viewModelScope.launch {
            try {
                val uri = (if (kind == AttachmentKind.MEDIA) gateway.pickVisualMedia() else gateway.pickDocument()) ?: return@launch
                when (val picked = files.inspectPicked(uri, kind)) {
                    is PickResult.Rejected -> notices.addWarning("Can't attach that: ${picked.message}")
                    PickResult.Cancelled -> Unit
                    is PickResult.Picked -> {
                        val id = java.util.UUID.randomUUID().toString()
                        val stored = files.storeCopy(picked.file.uri, id, picked.file.name)
                        val replyTo = (local.value.composer as? LocalComposer.Replying)?.id
                        val meta = MessageAttachment(
                            name = picked.file.name,
                            mime = picked.file.mime,
                            size = picked.file.size,
                            width = picked.file.width,
                            height = picked.file.height,
                        )
                        core.sendAttachment(peerIp, stored.absolutePath, meta, replyTo)
                        local.update { it.copy(composer = LocalComposer.Idle) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addError("Couldn't attach that file: ${e.userMessage()}")
            }
        }
    }

    // --- voice messages ---

    override fun startVoiceMessage() {
        closeOverlay()
        if (local.value.recording != null) return
        viewModelScope.launch {
            if (!gateway.requestPermission(Manifest.permission.RECORD_AUDIO)) {
                notices.addWarning("Microphone permission denied: Allow the microphone to record a voice message.")
                return@launch
            }
            try {
                val file = files.voiceFile()
                recorder.start(file)
                local.update { it.copy(recording = Recording(file, System.currentTimeMillis()), recordingElapsedMs = 0) }
                recordingJob?.cancel()
                recordingJob = viewModelScope.launch {
                    while (true) {
                        delay(RECORDING_TICK_MS)
                        local.update { l -> l.copy(recordingElapsedMs = l.recording?.let { r -> System.currentTimeMillis() - r.startedAtMs } ?: 0) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addError("Couldn't start recording: ${e.userMessage()}")
            }
        }
    }

    override fun cancelVoiceMessage() {
        if (local.value.recording == null) return
        recordingJob?.cancel()
        recorder.cancel()
        local.update { it.copy(recording = null, recordingElapsedMs = 0) }
    }

    override fun sendVoiceMessage() {
        val recording = local.value.recording ?: return
        recordingJob?.cancel()
        local.update { it.copy(recording = null, recordingElapsedMs = 0) }
        val replyTo = (local.value.composer as? LocalComposer.Replying)?.id
        viewModelScope.launch {
            val durationMs = try {
                recorder.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addWarning("Nothing recorded: ${e.userMessage()}")
                return@launch
            }
            if (durationMs < MIN_VOICE_MS) {
                files.remove(recording.file)
                notices.addWarning("Voice message too short: Hold the recording a little longer.")
                return@launch
            }
            val meta = MessageAttachment(name = AttachmentStore.VOICE_FILE_NAME, mime = AttachmentStore.VOICE_MIME, size = recording.file.length(), durationMs = durationMs)
            core.sendAttachment(peerIp, recording.file.absolutePath, meta, replyTo)
            local.update { it.copy(composer = LocalComposer.Idle) }
        }
    }

    override fun toggleVoice(msg: ChatMessage) {
        val file = (msg.attachment?.mediaSource() as? MediaSource.Path)?.file ?: return
        viewModelScope.launch {
            try {
                voice.toggle(msg.id, file, msg.attachment?.durationMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError("This voice message can't be played: ${e.userMessage()}")
            }
        }
    }

    override fun coverMessageSoon() {
        closeOverlay()
        notices.setSuccess("Cover messages are coming soon.")
    }

    // --- attachments ---

    override fun cancelIncomingTransfer(msg: ChatMessage) {
        val name = msg.attachment?.name ?: "this file"
        notices.setPrompt(
            Prompt(
                message = "Stop receiving $name? What has arrived so far is deleted and they are told you cancelled.",
                rightLabel = "Stop",
                isDestructive = true,
                onRight = { viewModelScope.launch { core.cancelIncomingTransfer(msg.id) } },
            ),
        )
    }

    override fun openAttachment(msg: ChatMessage) {
        // no file on disk in any of these, and this guards every surface that can reach here
        if (msg.status == MessageStatus.OFFERED || msg.status == MessageStatus.RECEIVING || msg.status.hasNoFile) return
        val att = msg.attachment ?: return
        val source = att.mediaSource()
        when (msg.kind) {
            MessageKind.IMAGE -> if (source != null) viewer.open(MediaViewerTarget.Image(source, att.name)) else setError("This attachment is no longer available on this device.")
            MessageKind.VIDEO -> if (source is MediaSource.Path) viewer.open(MediaViewerTarget.Video(source.file, att.name)) else setError("This video is no longer available on this device.")
            MessageKind.FILE, MessageKind.TEXT -> if (source is MediaSource.Path) {
                try {
                    openWith.openFile(source.file, att.mime)
                } catch (e: Exception) {
                    setError("No app on this phone can open that file type.")
                }
            } else {
                setError("This file is no longer available on this device.")
            }
        }
    }

    // --- calls, contact, navigation ---

    override fun startAudioCall() {
        if (uiState.value.isBlocked) {
            notices.addWarning("Contact is blocked: Unblock them to call.")
            return
        }
        viewModelScope.launch {
            gateway.deniedCallPermission(video = false)?.let { denied ->
                notices.addWarning(denied.needed("start a call"))
                return@launch
            }
            if (callEngine.startCall(peerIp, video = false)) {
                setError(null)
                navigator.openCall()
            } else {
                setError("You're already in a call. End it before starting a new one.")
            }
        }
    }

    override fun unarchive() {
        viewModelScope.launch { core.setContactFlags(peerIp, ContactFlagsPatch(isArchived = false)) }
    }

    override fun unblock() {
        viewModelScope.launch { core.setContactFlags(peerIp, ContactFlagsPatch(isBlocked = false)) }
    }

    override fun openSettings() = navigator.switchTab(Tab.ME)
    override fun openChatSettings() = navigator.push(ChatSettings(peerIp))
    override fun openContact() = navigator.push(ContactKey(peerIp))
    override fun loadOlder() {
        val before = uiState.value.oldestCursor ?: return
        if (!uiState.value.canLoadOlder) return
        window.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val page = core.messagesBefore(peerIp, before, PAGE)
                val oldest = page.firstOrNull()
                window.update {
                    it.copy(isLoading = false, isExhausted = page.size < PAGE, anchor = oldest?.let { m -> MessageCursor(m.ts, m.id) } ?: it.anchor)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                window.update { it.copy(isLoading = false) }
                setError("Couldn't load older messages: ${e.userMessage()}")
            }
        }
    }

    override fun jumpToLatest() {
        if (window.value != Window()) window.value = Window()
    }

    /** At the bottom with nothing hidden, the plain head serves again and the paged window is let go. */
    override fun reachedNewest() {
        if (window.value.anchor != null && !uiState.value.isDetachedFromLatest) window.value = Window()
    }

    override fun goBack() = navigator.pop()

    // --- reachability ---

    /**
     * The header's Ping. It is not only a diagnostic: an answer proves the peer is there, and the
     * core turns that proof straight into a drain — so anything queued for this chat goes now
     * instead of waiting out the peer's next scheduled probe.
     */
    override fun pingPeer() {
        if (local.value.isPinging) return
        pingResultJob?.cancel()
        local.update { it.copy(isPinging = true, pingResult = null) }
        viewModelScope.launch {
            val rtt = core.pingPeer(peerIp)
            local.update {
                it.copy(
                    isPinging = false,
                    pingResult = if (rtt >= 0) PingResult.REACHABLE else PingResult.OFFLINE,
                )
            }
            pingResultJob = viewModelScope.launch {
                delay(PING_RESULT_HOLD_MS)
                local.update { it.copy(pingResult = null) }
            }
        }
    }

    // --- search ---

    fun beginSearch() = local.update { it.copy(isSearching = true) }
    override fun endSearch() = local.update { it.copy(isSearching = false, searchQuery = "") }
    override fun setSearchQuery(value: String) = local.update { it.copy(searchQuery = value) }

    // --- message details, menu, reactions ---

    /**
     * Double tap on a message that has not gone out: attempt its send now rather than waiting for
     * the next outbox cycle. A send action carries its own message's id, so the message names it.
     */
    override fun retrySendNow(msg: ChatMessage) {
        viewModelScope.launch {
            try {
                core.retryActionNow(msg.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addWarning("Couldn't retry that message: ${e.userMessage()}")
            }
        }
    }

    /**
     * Answers a large-file offer. Free space goes with the acceptance so the core can turn it
     * into a refusal rather than start a transfer this device cannot finish.
     */
    override fun acceptOffer(msg: ChatMessage) {
        viewModelScope.launch {
            try {
                core.acceptAttachmentOffer(msg.id, files.freeBytes())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addWarning("Couldn't accept that file: ${e.userMessage()}")
            }
        }
    }

    override fun declineOffer(msg: ChatMessage) {
        viewModelScope.launch {
            try {
                core.declineAttachmentOffer(msg.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addWarning("Couldn't decline that file: ${e.userMessage()}")
            }
        }
    }

    /** A link tapped inside a bubble. Launching the browser is platform I/O, so it happens here. */
    override fun openLink(url: String) {
        try {
            openWith.openUrl(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notices.addWarning("Can't open: $url")
        }
    }

    override fun toggleMessageDetails(msg: ChatMessage) = local.update { it.copy(expandedMessageId = if (it.expandedMessageId == msg.id) null else msg.id) }
    override fun openMessageMenu(msg: ChatMessage) = local.update { it.copy(overlay = LocalOverlay.Menu(msg.id)) }
    override fun closeMessageMenu() = closeOverlay()

    override fun openReactions(msg: ChatMessage) {
        if (msg.reactions.isEmpty()) return
        sheets.open(SheetRequest.Reactions(msg.id, peerIp))
    }

    override fun reactWith(emoji: String) {
        val id = menuId()
        closeOverlay()
        if (id == null) return
        viewModelScope.launch {
            core.reactToMessage(id, emoji)
            prefs.recordRecentReaction(emoji)
        }
    }

    override fun openReactionPicker() {
        val id = menuId() ?: return
        closeOverlay()
        sheets.open(SheetRequest.ReactionPicker(id, peerIp))
    }

    override fun beginEdit() {
        val msg = message(menuId())
        closeOverlay()
        if (msg != null && msg.direction == MessageDirection.OUT && msg.kind == MessageKind.TEXT && !msg.isDeleted) {
            local.update { it.copy(composer = LocalComposer.Editing(msg.id), draft = msg.body) }
        }
    }

    override fun copyMessage() {
        val msg = message(menuId())
        closeOverlay()
        if (msg != null && msg.body.isNotEmpty()) {
            val clip = ClipData.newPlainText("message", msg.body)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            }
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(clip)
        }
    }

    override fun openForward() = local.update { it.copy(overlay = (it.overlay as? LocalOverlay.Menu)?.let { m -> LocalOverlay.Forward(m.id) } ?: LocalOverlay.None) }
    override fun closeForward() = closeOverlay()

    override fun forwardTo(targetIp: String) {
        val id = (local.value.overlay as? LocalOverlay.Forward)?.id
        closeOverlay()
        if (id != null) viewModelScope.launch { core.forwardMessage(id, targetIp) }
    }

    override fun openActionsSheet() {
        val id = menuId() ?: return
        closeOverlay()
        sheets.open(SheetRequest.MessageActivity(id, peerIp))
    }

    override fun replyFromMenu() {
        message(menuId())?.let(::beginReply) ?: closeOverlay()
    }

    override fun deleteForMe() = confirmDelete("Delete for me\n\nRemove this message from this device only?") { core.deleteForMe(it) }
    override fun deleteForEveryone() = confirmDelete("Delete for everyone\n\nDelete this message for both of you?") { core.deleteForEveryone(it) }

    private fun confirmDelete(message: String, run: suspend (String) -> Unit) {
        val id = menuId()
        closeOverlay()
        if (id == null) return
        notices.setPrompt(
            Prompt(
                message = message,
                rightLabel = "Delete",
                isDestructive = true,
                onRight = {
                    if ((local.value.composer as? LocalComposer.Editing)?.id == id) cancelEdit()
                    viewModelScope.launch { run(id) }
                },
            ),
        )
    }

    override fun clearError() = setError(null)

    private fun setError(message: String?) = local.update { it.copy(error = message) }

    override fun onCleared() {
        super.onCleared()
        stopTyping()
        cancelVoiceMessage()
    }

    private companion object {
        const val TYPING_INTERVAL_MS = 4_000L
        const val TYPING_IDLE_MS = 5_000L

        const val PING_RESULT_HOLD_MS = 4_000L

        const val PRESENCE_REFRESH_MS = 30_000L

        const val RECORDING_TICK_MS = 500L

        /** anything shorter is a slip of the finger, not a message */

        const val MIN_VOICE_MS = 1_000L

        /** rows one older page brings, and the most the window holds before the newest fall off its far end */

        const val PAGE = 100

        const val WINDOW_MAX = 600
    }
}

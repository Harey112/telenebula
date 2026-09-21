package com.telenebula.core

import android.content.Context
import com.telenebula.core.events.CoreEventBus
import com.telenebula.core.events.CoreInvalidations
import com.telenebula.core.events.CoreInvalidations.Companion.BUMP_COALESCE_MS
import com.telenebula.core.model.MessageCursor
import com.telenebula.core.model.ContactPrivacyPrefs
import com.telenebula.core.model.BackupSummary
import com.telenebula.core.model.CallLog
import com.telenebula.core.model.ChatLink
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.ChatSummary
import com.telenebula.core.model.ChatView
import com.telenebula.core.model.Contact
import com.telenebula.core.model.ContactFlagsPatch
import com.telenebula.core.model.ContactNotificationPrefs
import com.telenebula.core.model.CoreStartConfig
import com.telenebula.core.model.EnvelopeType
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageAttachment
import com.telenebula.core.model.NetworkStats
import com.telenebula.core.model.NotificationPrefs
import com.telenebula.core.model.OutboundSignal
import com.telenebula.core.model.PeerQueueState
import com.telenebula.core.model.PeerStats
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.Profile
import com.telenebula.core.model.StorageStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import com.telenebula.core.events.CoreEvent
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The app's view of the messaging core. Queries return complete view models from one call each;
 * updates arrive through [CoreEventBus] — nothing here polls.
 *
 * Suspend queries answer immediately with a safe fallback while the store is not open yet (the
 * first frames before bootstrap); the reactive flows instead wait for [openStore], so a screen
 * never renders an empty frame it is about to replace.
 *
 * The list views every tab renders ([chatSummaries], [contacts], [recentCallLogs]) and the
 * recently opened chats ([chatViewState]) are sticky shared states: the last result stays readable
 * through `.value` after the last subscriber leaves, so a screen seeds its first frame from it and
 * only the re-query runs in the background.
 */
class CoreClient internal constructor(
    private val core: MessagingCore,
    private val paths: CorePaths,
    private val bus: CoreEventBus,
    private val errorSink: CoreErrorSink,
    private val io: CoroutineDispatcher,
    private val services: CoreServices,
) {
    constructor(
        context: Context,
        errorSink: CoreErrorSink,
        io: CoroutineDispatcher = Dispatchers.IO,
    ) : this(CorePaths(context), errorSink, io, AndroidCoreServices(context))

    private constructor(
        paths: CorePaths,
        errorSink: CoreErrorSink,
        io: CoroutineDispatcher,
        services: CoreServices,
    ) : this(TnCore(paths), paths, CoreEventBus, errorSink, io, services)

    private val storeReady = CompletableDeferred<Unit>()
    private val openLock = Mutex()
    private val invalidations = CoreInvalidations(bus)
    private val scope = CoroutineScope(SupervisorJob() + io)

    /** [STICKY], but it keeps hold of the subscriber count so a watched chat can be told from an idle one. */
    private class StickyStart : SharingStarted {
        @Volatile
        private var counts: StateFlow<Int>? = null

        val isWatched: Boolean get() = (counts?.value ?: 0) > 0

        override fun command(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> {
            counts = subscriptionCount
            return STICKY.command(subscriptionCount)
        }
    }

    private class ChatViewEntry(val scope: CoroutineScope, val started: StickyStart, val state: StateFlow<ChatView?>)

    // access-ordered so the chat opened longest ago is the one dropped
    private val chatViews = LinkedHashMap<String, ChatViewEntry>(CHAT_VIEW_CACHE, 0.75f, true)

    init {
        scope.launch {
            bus.events.filterIsInstance<CoreEvent.EngineFault>().collect { errorSink.report("${it.what}: ${it.message}") }
        }
    }

    // --- lifecycle ---

    /** Opens the SQLite store (running the migrations). Idempotent. */
    suspend fun openStore() = withContext(io) {
        openLock.withLock {
            if (storeReady.isCompleted) return@withLock
            core.openStore()
            storeReady.complete(Unit)
        }
    }

    /** Boots the engine: listener, outbox, event pump. Idempotent; throws on failure. */
    suspend fun start(profile: Profile, prefs: Prefs, appVersion: String) {
        openStore()
        val config = CoreStartConfig(
            overlayIp = profile.overlayIp,
            displayName = profile.certName,
            msgPort = profile.msgPort,
            sendReadReceipts = prefs.sendReadReceipts,
            appVersion = appVersion,
        )
        withContext(io) { core.start(config) }
    }

    suspend fun stop() = withContext(io) { core.stop() }

    suspend fun coreVersion(): String = withContext(io) { core.version }

    suspend fun setSendReadReceipts(enabled: Boolean) = command { core.setSendReadReceipts(enabled) }

    suspend fun setOnline(isOnline: Boolean) = command { core.setOnline(isOnline) }

    fun setNotificationPrefs(prefs: NotificationPrefs) = services.setNotificationPrefs(prefs)

    /** Keeps the process (core + tunnel) alive while the app is backgrounded. */
    fun startBackgroundService() = services.startBackgroundService()

    fun stopBackgroundService() = services.stopBackgroundService()

    /**
     * Keeps the service notification's tunnel label current — and tells the engine, which stops
     * probing peers while the overlay is down and drains everything the moment it returns.
     */
    fun setTunnelState(running: Boolean) {
        services.setTunnelState(running)
        scope.launch { command { core.setTunnelState(running) } }
    }

    /** Re-runs every live query. */
    fun refresh() = invalidations.refresh()

    // --- queries (one call each) ---

    suspend fun chatView(peerIp: String, limit: Int = 200): ChatView =
        query(EMPTY_CHAT_VIEW) { core.chatView(peerIp, limit) }

    suspend fun readChatSummaries(): List<ChatSummary> = query(emptyList()) { core.chatSummaries() }

    suspend fun readContacts(): List<Contact> = query(emptyList()) { core.contacts() }

    suspend fun contact(ip: String): Contact? = query(null) { core.contact(ip) }

    /** The contact as last read by [contacts]; null before the first read or when unknown. */
    fun cachedContact(ip: String): Contact? = contacts.value?.firstOrNull { it.ip == ip }

    suspend fun message(id: String): ChatMessage? = query(null) { core.message(id) }

    /** Every action on one message, oldest first. */
    suspend fun messageActions(id: String): List<MessageAction> = query(emptyList()) { core.messageActions(id) }

    /** Case-insensitive search in one chat, newest first. */
    suspend fun searchMessages(peerIp: String, text: String, limit: Int = 100): List<ChatMessage> =
        query(emptyList()) { core.searchMessages(peerIp, text, limit) }

    suspend fun exportChatText(peerIp: String, label: String): String = withContext(io) {
        try {
            core.exportChatText(peerIp, label)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorSink.report("Export failed: ${e.messageOrName()}")
            ""
        }
    }

    /** Newest first, capped. */
    suspend fun allCallLogs(limit: Int = 200): List<CallLog> = query(emptyList()) { core.allCallLogs(limit) }

    suspend fun callLogs(peerIp: String, limit: Int = 30): List<CallLog> =
        query(emptyList()) { core.callLogs(peerIp, limit) }

    /** Messages carrying an attachment in one chat, newest first. */
    suspend fun chatMedia(peerIp: String, limit: Int = 200): List<ChatMessage> =
        query(emptyList()) { core.chatMedia(peerIp, limit) }

    /** URLs found in one chat's messages, newest first. */
    suspend fun chatLinks(peerIp: String, limit: Int = 200): List<ChatLink> =
        query(emptyList()) { core.chatLinks(peerIp, limit) }

    suspend fun chatMediaCount(peerIp: String): Int = query(0) { core.chatMediaCount(peerIp) }

    suspend fun chatLinkMessageCount(peerIp: String): Int = query(0) { core.chatLinkMessageCount(peerIp) }

    suspend fun unreadTotal(): Int = query(0) { core.unreadTotal() }

    suspend fun peerStats(peerIp: String): PeerStats = query(EMPTY_PEER_STATS) { core.peerStats(peerIp) }

    suspend fun networkStats(): NetworkStats = query(EMPTY_NETWORK_STATS) { core.networkStats() }

    suspend fun storageStats(): StorageStats = query(EMPTY_STORAGE_STATS) { core.storageStats() }

    // --- reactive queries: re-run after each (debounced) invalidation ---

    /** Every chat, archived ones included; null until the first read. */
    val chatSummaries: StateFlow<List<ChatSummary>?> =
        reactive(invalidations.summaries()) { readChatSummaries() }.stateIn(scope, STICKY, null)

    /** Every contact, blocked ones included; null until the first read. */
    val contacts: StateFlow<List<Contact>?> =
        reactive(invalidations.summaries()) { readContacts() }.stateIn(scope, STICKY, null)

    /** The newest [RECENT_CALL_LOGS] calls across every peer; null until the first read. */
    val recentCallLogs: StateFlow<List<CallLog>?> =
        reactive(invalidations.callLogs()) { allCallLogs(RECENT_CALL_LOGS) }.stateIn(scope, STICKY, null)

    /**
     * One chat's view, shared by everyone looking at that chat and kept for the [CHAT_VIEW_CACHE]
     * most recently opened chats so reopening one is instant.
     */
    fun chatViewState(peerIp: String): StateFlow<ChatView?> = synchronized(chatViews) {
        val entry = chatViews.getOrPut(peerIp) {
            val entryScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
            val started = StickyStart()
            ChatViewEntry(entryScope, started, chatFlow(peerIp, null, CHAT_HEAD_LIMIT).stateIn(entryScope, started, null))
        }
        trimChatViews()
        entry.state
    }

    /** Its own scope, so dropping a chat also ends the sharing coroutine and releases the view it cached. */
    private fun trimChatViews() {
        while (chatViews.size > CHAT_VIEW_CACHE) {
            val idle = chatViews.entries.firstOrNull { !it.value.started.isWatched } ?: return
            chatViews.remove(idle.key)?.scope?.cancel()
        }
    }

    /** Starts reading a chat before its screen exists (called from the row tap). */
    fun warmChat(peerIp: String) {
        val state = chatViewState(peerIp)
        scope.launch { state.first { it != null } }
    }

    fun chatViewFlow(peerIp: String): Flow<ChatView> = chatViewState(peerIp).filterNotNull()

    /** The window from [from] towards the newest, at most [limit] rows, kept current while the chat changes. */
    fun chatWindowFlow(peerIp: String, from: MessageCursor, limit: Int): Flow<ChatView> = chatFlow(peerIp, from, limit)

    /**
     * One chat kept current: a full read first and whenever rows are added or removed, otherwise
     * only the rows the engine named are read again and patched in. Announcements that arrive
     * while a read runs are merged, so a burst costs one read of the union.
     */
    private fun chatFlow(peerIp: String, from: MessageCursor?, limit: Int): Flow<ChatView> = channelFlow {
        storeReady.await()
        val pending = ChatDeltas()
        val wake = Channel<Unit>(Channel.CONFLATED)
        launch {
            invalidations.chatDeltas(peerIp).collect {
                pending.add(it)
                wake.send(Unit)
            }
        }
        var view: ChatView? = null
        for (unit in wake) {
            val delta = pending.take() ?: continue
            val current = view
            val next = when {
                current == null || delta.messageIds == null -> query(EMPTY_CHAT_VIEW) {
                    if (from == null) core.chatView(peerIp, limit) else core.chatViewFrom(peerIp, from, limit)
                }
                delta.messageIds.isEmpty() && !delta.hasContactChange -> continue
                else -> current.patched(query(EMPTY_CHAT_VIEW) { core.chatRows(peerIp, delta.messageIds) }, delta.hasContactChange)
            }
            view = next
            send(next)
        }
    }.flowOn(io)

    /** Announcements merged between reads, the same way the engine merges them between flushes. */
    private class ChatDeltas {
        private var hasAny = false
        private var isStructural = false
        private var hasContactChange = false
        private val ids = LinkedHashSet<String>()

        @Synchronized
        fun add(event: CoreEvent.ChatChanged) {
            hasAny = true
            hasContactChange = hasContactChange || event.hasContactChange
            val changed = event.messageIds
            if (changed == null) isStructural = true else ids += changed
            if (ids.size > MAX_PATCH_IDS) isStructural = true
        }

        @Synchronized
        fun take(): CoreEvent.ChatChanged? {
            if (!hasAny) return null
            val taken = CoreEvent.ChatChanged("", if (isStructural) null else ids.toSet(), hasContactChange)
            hasAny = false
            isStructural = false
            hasContactChange = false
            ids.clear()
            return taken
        }
    }

    suspend fun messagesBefore(peerIp: String, before: MessageCursor, limit: Int): List<ChatMessage> =
        query(emptyList()) { core.messagesBefore(peerIp, before, limit) }

    fun chatSummariesFlow(): Flow<List<ChatSummary>> = chatSummaries.filterNotNull()

    fun contactsFlow(): Flow<List<Contact>> = contacts.filterNotNull()

    fun contactFlow(ip: String): Flow<Contact?> = contactsFlow().map { list -> list.firstOrNull { it.ip == ip } }.distinctUntilChanged()

    /** One peer's calls, newest first, re-read while the store changes. */
    fun callLogsFlow(peerIp: String, limit: Int): Flow<List<CallLog>> = reactive(invalidations.callLogs()) { callLogs(peerIp, limit) }

    /** Emits once at once, then whenever a call is logged. */
    fun callLogChanges(): Flow<Unit> = ticks(invalidations.callLogs())

    /** Emits once at once, then (debounced) whenever any chat, contact or stat changes. */
    fun storeChanges(): Flow<Unit> = ticks(invalidations.summaries())

    /** Emits once at once, then (debounced) whenever this chat's messages, actions or contact change. */
    fun chatChanges(peerIp: String): Flow<Unit> = ticks(invalidations.chat(peerIp))

    /** One message, re-read while its chat changes (sheets that outlive the list). */
    fun messageFlow(id: String, peerIp: String): Flow<ChatMessage?> = reactive(invalidations.chat(peerIp)) { message(id) }

    fun messageActionsFlow(id: String, peerIp: String): Flow<List<MessageAction>> = reactive(invalidations.chat(peerIp)) { messageActions(id) }

    fun unreadTotalFlow(): Flow<Int> = reactive(invalidations.summaries()) { unreadTotal() }

    // --- commands (failures go to the error sink) ---

    suspend fun sendText(peerIp: String, body: String, replyToId: String? = null, isCovered: Boolean = false) =
        command { core.sendText(peerIp, body, replyToId, isCovered) }

    /**
     * [path] is the stored copy (see [CorePaths.attachmentFile]); the core streams it from there,
     * and [meta]'s own `uri` is ignored.
     */
    suspend fun sendAttachment(peerIp: String, path: String, meta: MessageAttachment, replyToId: String? = null, isCovered: Boolean = false) =
        command { core.sendAttachment(peerIp, path, meta, replyToId, isCovered) }

    suspend fun reactToMessage(messageId: String, emoji: String) = command { core.reactToMessage(messageId, emoji) }

    suspend fun editMessage(messageId: String, newBody: String) = command { core.editMessage(messageId, newBody) }

    suspend fun deleteForEveryone(messageId: String) = command { core.deleteForEveryone(messageId) }

    /** Local-only, so the core announces nothing: the client invalidates for it (same below). */
    suspend fun deleteForMe(messageId: String) = localChange { core.deleteForMe(messageId) }

    suspend fun forwardMessage(sourceMessageId: String, targetPeerIp: String) =
        command { core.forwardMessage(sourceMessageId, targetPeerIp) }

    suspend fun retryAction(actionId: String) = command { core.retryAction(actionId) }

    /** Try now rather than on the next cycle: an extra attempt while pending, a restart once it gave up. */
    suspend fun retryActionNow(actionId: String) = command { core.retryActionNow(actionId) }

    /** Answers a large-file offer. [freeBytes] lets the core refuse what would not fit. */
    suspend fun acceptAttachmentOffer(transferId: String, freeBytes: Long) =
        command { core.acceptAttachmentOffer(transferId, freeBytes) }

    suspend fun declineAttachmentOffer(transferId: String) = command { core.declineAttachmentOffer(transferId) }

    suspend fun cancelIncomingTransfer(transferId: String) = command { core.cancelIncomingTransfer(transferId) }

    suspend fun cancelAction(actionId: String) = command { core.cancelAction(actionId) }

    /** Re-queues failed actions; an empty peer means everywhere. Returns how many. */
    suspend fun retryFailedActions(peerIp: String = ""): Int = withContext(io) {
        try {
            core.retryFailedActions(peerIp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorSink.report("Retry failed: ${e.messageOrName()}")
            0
        }
    }

    /** How many actions are still waiting to reach this peer. */
    suspend fun queuedActionCount(peerIp: String): Int = query(0) { core.queuedActionCount(peerIp) }

    /**
     * Probe this peer now and deliver whatever it is holding. What the user means by "send it
     * already": the queue stops waiting for the peer's next scheduled probe.
     */
    suspend fun drainNow(peerIp: String) = command { core.drainNow(peerIp) }

    /** This peer's queue as the engine sees it, for a screen opening before any event arrives. */
    suspend fun peerQueue(peerIp: String): PeerQueueState =
        query(PeerQueueState(ip = peerIp)) { core.peerQueue(peerIp) }

    suspend fun logCall(log: CallLog) = command { core.logCall(log) }

    suspend fun deleteCallLogs(ids: List<String>) = command { core.deleteCallLogs(ids) }

    /** Also dismisses the chat's system notification: reading in the app ends the thread. */
    suspend fun markChatRead(peerIp: String) {
        command { core.markChatRead(peerIp) }
        services.clearChatNotification(peerIp)
    }

    suspend fun clearHistory(peerIp: String) = localChange { core.clearHistory(peerIp) }

    /** False when the core refused; the failure has already been reported. */
    suspend fun clearAllHistory(): Boolean {
        val ok = commandOk { core.clearAllHistory() }
        invalidations.refresh()
        return ok
    }

    suspend fun upsertContact(ip: String, name: String) = localChange { core.upsertContact(ip, name) }

    suspend fun ensureContact(ip: String, name: String) = localChange { core.ensureContact(ip, name) }

    suspend fun updateContactDetails(ip: String, name: String, nickname: String, notes: String) =
        localChange { core.updateContactDetails(ip, name, nickname, notes) }

    suspend fun deleteContact(ip: String) = localChange { core.deleteContact(ip) }

    /** Moves a contact and its history to a new address, merging with any contact already there. */
    suspend fun changeContactIp(oldIp: String, newIp: String) = command { core.changeContactIp(oldIp, newIp) }

    suspend fun setContactFlags(ip: String, patch: ContactFlagsPatch) = localChange { core.setContactFlags(ip, patch) }

    /** null restores "use global" for the contact. */
    suspend fun setContactNotifications(ip: String, prefs: ContactNotificationPrefs?) =
        localChange { core.setContactNotifications(ip, prefs) }

    suspend fun setContactPrivacy(ip: String, prefs: ContactPrivacyPrefs) = localChange { core.setContactPrivacy(ip, prefs) }

    // --- storage and backups ---

    /** Deletes attachment files no message references; returns the bytes freed. */
    suspend fun clearOrphanAttachments(): Long = withContext(io) {
        try {
            core.clearOrphanAttachments()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorSink.report("Storage cleanup failed: ${e.messageOrName()}")
            0L
        }
    }

    /** Writes a tar backup (never the private key) into the cache directory. Throws on failure. */
    suspend fun createBackup(appVersion: String): BackupSummary = withContext(io) {
        core.createBackup(paths.prefsPath, paths.backupFile().path, appVersion)
    }

    /** Merges a backup archive into this device; existing rows are never touched. Throws on failure. */
    suspend fun importBackup(archivePath: String): BackupSummary =
        withContext(io) { core.importBackup(archivePath, paths.prefsPath) }.also { invalidations.refresh() }

    // --- network waits (the timeout is the only cancellation) ---

    /** Round-trip in ms, or -1 when the peer did not answer within `timeoutMs` (0 = the default). */
    suspend fun pingPeer(peerIp: String, timeoutMs: Int = 0): Long = withContext(io) {
        try {
            core.pingPeer(peerIp, timeoutMs)
        } catch (e: CancellationException) {
            // the caller gave up waiting; reporting "unreachable" for that would be a lie it acts on
            throw e
        } catch (_: Exception) {
            -1L
        }
    }

    /** Delivers one signalling envelope; throws when the peer is unreachable. */
    suspend fun sendSignal(peerIp: String, signal: OutboundSignal, timeoutMs: Int = 0) = withContext(io) {
        core.sendSignal(peerIp, signal, timeoutMs)
    }

    /** Best-effort typing indicator; silently dropped when the peer is unreachable. */
    fun sendTyping(peerIp: String, isTyping: Boolean) {
        scope.launch {
            try {
                sendSignal(peerIp, OutboundSignal(type = EnvelopeType.TYPING, typing = isTyping), TYPING_TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    // --- internals ---

    /** A store that is not open yet answers with the fallback quietly; anything else is a real failure and is reported. */
    private suspend inline fun <T> query(fallback: T, crossinline read: () -> T): T = withContext(io) {
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!e.isNotRunning()) errorSink.report("Messaging engine error: ${e.messageOrName()}")
            fallback
        }
    }

    /** The first tick fires the moment the store is open; only the invalidations behind it are coalesced. */
    private fun ticks(invalidation: Flow<Unit>): Flow<Unit> = flow {
        storeReady.await()
        emitAll(invalidation.debounceAfterFirst(BUMP_COALESCE_MS))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> reactive(invalidation: Flow<Unit>, query: suspend () -> T): Flow<T> =
        ticks(invalidation).mapLatest { query() }.flowOn(io)

    private suspend inline fun command(crossinline block: () -> Unit) = withContext(io) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorSink.report("Messaging engine error: ${e.messageOrName()}")
        }
    }

    /**
     * A [command] that only touches the local store. The core announces nothing for those, so
     * without this every live query would keep its stale result until something else invalidated
     * it (a deleted message stayed on screen until the chat was reopened).
     */
    private suspend inline fun localChange(crossinline block: () -> Unit) {
        command(block)
        invalidations.refresh()
    }

    /** [command] that also says whether the block ran, for a caller that must not celebrate a failure. */
    private suspend inline fun commandOk(crossinline block: () -> Unit): Boolean = withContext(io) {
        try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorSink.report("Messaging engine error: ${e.messageOrName()}")
            false
        }
    }

    /** Rows the window already shows are replaced from [rows]; rows outside it are ignored. */
    private fun ChatView.patched(rows: ChatView, hasContactChange: Boolean): ChatView {
        val fresh = rows.messages.associateBy { it.id }
        val shown = messages.mapTo(HashSet(messages.size * 2)) { it.id }
        val nextMessages = if (fresh.isEmpty()) messages else messages.map { fresh[it.id] ?: it }
        val nextActions = if (fresh.isEmpty()) actions else LinkedHashMap(actions).apply {
            for (id in fresh.keys) {
                if (id !in shown) continue
                val list = rows.actions[id]
                if (list == null) remove(id) else put(id, list)
            }
        }
        val nextSources = if (fresh.isEmpty()) replySources else LinkedHashMap(replySources).apply {
            for ((id, message) in fresh) if (containsKey(id)) put(id, message)
        }
        return ChatView(
            contact = if (hasContactChange) rows.contact else contact,
            messages = nextMessages,
            actions = nextActions,
            replySources = nextSources,
        )
    }

    private fun Exception.messageOrName(): String = message ?: javaClass.simpleName

    private fun Exception.isNotRunning(): Boolean = this is CoreException && kind == CoreException.Kind.NOT_RUNNING

    companion object {
        private const val TYPING_TIMEOUT_MS = 1500
        private const val CHAT_VIEW_CACHE = 8
        const val CHAT_HEAD_LIMIT = 200

        /** more changed rows than this and a plain re-read of the window is the cheaper query */
        private const val MAX_PATCH_IDS = 200
        private const val RECENT_CALL_LOGS = 300

        // the upstream stops 5 s after the last subscriber leaves (a tab switch and back costs
        // nothing), but the last value is never expired: it seeds the next screen's first frame
        private val STICKY = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = Long.MAX_VALUE)

        private val EMPTY_CHAT_VIEW = ChatView()
        private val EMPTY_PEER_STATS = PeerStats()
        private val EMPTY_NETWORK_STATS = NetworkStats()
        private val EMPTY_STORAGE_STATS = StorageStats()

        /** Lower-case, with no IPv4-mapped prefix, brackets or zone id. */
        fun normalizeIp(ip: String?): String = Ip.normalize(ip)
    }
}

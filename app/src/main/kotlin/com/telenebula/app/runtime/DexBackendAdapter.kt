package com.telenebula.app.runtime

import com.telenebula.app.platform.AppLock
import com.telenebula.app.platform.AttachmentStore
import com.telenebula.app.platform.ActionQueue
import com.telenebula.app.platform.CertInspector
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.core.CoreClient
import com.telenebula.core.PeerQueueStore
import com.telenebula.core.PresenceStore
import com.telenebula.core.TransferProgressStore
import com.telenebula.core.TypingStore
import com.telenebula.core.model.CallLog
import com.telenebula.core.model.CallNotificationPrefs
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.ChatSummary
import com.telenebula.core.model.ChatTextSize
import com.telenebula.core.model.ChatView
import com.telenebula.core.model.Contact
import com.telenebula.core.model.ContactFlagsPatch
import com.telenebula.core.model.ContactNotificationPrefs
import com.telenebula.core.model.ContactPrivacyPrefs
import com.telenebula.core.model.CoverRevealGate
import com.telenebula.core.model.InAppNotificationPrefs
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageDensity
import com.telenebula.core.model.MessageNotificationPrefs
import com.telenebula.core.model.NebulaLogLevel
import com.telenebula.core.model.NotificationPrefs
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.PresencePrefs
import com.telenebula.core.model.QuietHours
import com.telenebula.core.model.ThemeMode
import com.telenebula.core.model.CallOutcome as CoreCallOutcome
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import com.telenebula.core.model.MessageAttachment
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus
import com.telenebula.core.model.PeerPresence
import com.telenebula.core.model.PeerQueueState
import com.telenebula.core.model.Profile
import com.telenebula.dex.DexBackend
import com.telenebula.dex.DexCallCommand
import com.telenebula.dex.DexCallEvent
import com.telenebula.dex.DexFile
import com.telenebula.dex.DexUpload
import com.telenebula.dex.Limits
import com.telenebula.dex.wire.DexAccount
import com.telenebula.dex.wire.DexAttachment
import com.telenebula.dex.wire.DexCallLog
import com.telenebula.dex.wire.DexCallNotifications
import com.telenebula.dex.wire.DexCallOutcome
import com.telenebula.dex.wire.DexCallState
import com.telenebula.dex.wire.DexChat
import com.telenebula.dex.wire.DexChatLink
import com.telenebula.dex.wire.DexChatView
import com.telenebula.dex.wire.DexContact
import com.telenebula.dex.wire.DexContactDetail
import com.telenebula.dex.wire.DexContactFlags
import com.telenebula.dex.wire.DexContactNotifications
import com.telenebula.dex.wire.DexContactPrivacy
import com.telenebula.dex.wire.DexDensity
import com.telenebula.dex.wire.DexDiagnostics
import com.telenebula.dex.wire.DexDirection
import com.telenebula.dex.wire.DexIdentity
import com.telenebula.dex.wire.DexInAppNotifications
import com.telenebula.dex.wire.DexLogLevel
import com.telenebula.dex.wire.DexMessage
import com.telenebula.dex.wire.DexMessageKind
import com.telenebula.dex.wire.DexMessageNotifications
import com.telenebula.dex.wire.DexMessageStatus
import com.telenebula.dex.wire.DexNetwork
import com.telenebula.dex.wire.DexNotifications
import com.telenebula.dex.wire.DexPeerRow
import com.telenebula.dex.wire.DexPeerStats
import com.telenebula.dex.wire.DexPingResult
import com.telenebula.dex.wire.DexPresence
import com.telenebula.dex.wire.DexPresencePrefs
import com.telenebula.dex.wire.DexQueue
import com.telenebula.dex.wire.DexQuietHours
import com.telenebula.dex.wire.DexReplyPreview
import com.telenebula.dex.wire.DexRevealGate
import com.telenebula.dex.wire.DexSendState
import com.telenebula.dex.wire.DexSettings
import com.telenebula.dex.wire.DexSettingsPatch
import com.telenebula.dex.wire.DexStorage
import com.telenebula.dex.wire.DexTextSize
import com.telenebula.dex.wire.DexThemeMode
import com.telenebula.dex.wire.DexUpdatePrefs
import com.telenebula.dex.wire.DexUpdates
import com.telenebula.vpn.NebulaVpnController
import com.telenebula.vpn.model.HostmapEntry
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * The Dex server's view of this phone: every read is one of the core's coarse reactive views
 * mapped to the wire's types, every command is the same core call the phone's own screens make.
 */
class DexBackendAdapter(
    scope: CoroutineScope,
    private val runtime: AppRuntime,
    private val core: CoreClient,
    private val prefs: PrefsRepository,
    private val typingStore: TypingStore,
    private val presenceStore: PresenceStore,
    private val transfers: TransferProgressStore,
    private val peerQueues: PeerQueueStore,
    private val files: AttachmentStore,
    private val appLock: AppLock,
    private val calls: DexCallBridge,
    private val vpn: NebulaVpnController,
    private val updateMonitor: UpdateMonitor,
    private val callTrail: () -> List<String>,
    private val appVersion: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : DexBackend {
    private val profile: StateFlow<Profile?> = runtime.profile

    override val me: StateFlow<DexIdentity?> = profile.map { p -> p?.let { DexIdentity(it.certName, it.overlayIp) } }
        .stateIn(scope, SharingStarted.Eagerly, profile.value?.let { DexIdentity(it.certName, it.overlayIp) })

    override val overlayNetworks: StateFlow<List<String>> = profile.map { it?.networks.orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, profile.value?.networks.orEmpty())

    override fun chats(): Flow<List<DexChat>> = combine(core.chatSummariesFlow(), core.contactsFlow()) { summaries, contacts ->
        val byIp = contacts.associateBy { it.ip }
        summaries.filter { it.lastTs != null || byIp.containsKey(it.ip) }.map { s -> s.toChat(byIp[s.ip]) }
    }.distinctUntilChanged()

    override fun contacts(): Flow<List<DexContact>> = core.contactsFlow().map { list -> list.map { it.toContact() } }.distinctUntilChanged()

    override fun chat(peer: String): Flow<DexChatView> =
        combine(core.chatViewFlow(peer), transfers.progress, peerQueues.queues, prefs.prefs.map { it.coverRevealGate }.distinctUntilChanged()) { view, progress, queues, gate ->
            view.toView(peer, progress, queues[peer], gate)
        }

    override suspend fun messagesBefore(peer: String, beforeTs: Long, beforeId: String, limit: Int): List<DexMessage> {
        val page = core.messagesBefore(peer, com.telenebula.core.model.MessageCursor(beforeTs, beforeId), limit.coerceIn(1, Limits.CHAT_PAGE))
        return page.map { it.toMessage(emptyList(), null, emptyMap(), emptySet(), null, null) }
    }

    override fun presence(): Flow<Map<String, DexPresence>> = presenceStore.presence.map { entries ->
        val stamp = now()
        entries.mapValues { (_, entry) -> (PresenceStore.fresh(entry, stamp) ?: PeerPresence.OFFLINE).toWire() }
    }.distinctUntilChanged()

    override fun typing(): Flow<Set<String>> = typingStore.typing

    override fun queues(): Flow<List<DexQueue>> = peerQueues.queues.map { map -> map.values.map { it.toQueue() } }.distinctUntilChanged()

    override suspend fun freeBytes(): Long = withContext(io) { files.freeBytes() }

    override suspend fun sendText(peer: String, body: String, replyTo: String?, isCovered: Boolean) {
        val text = body.trim()
        require(text.isNotEmpty()) { "Nothing to send" }
        core.sendText(peer, text, replyTo?.takeIf { it.isNotBlank() }, isCovered)
    }

    override fun newUploadFile(name: String): File = files.uploadFile(name)

    override suspend fun sendUpload(upload: DexUpload) {
        require(upload.size > 0 && upload.file.isFile) { "The upload is empty" }
        val meta = MessageAttachment(
            name = upload.name.ifBlank { upload.file.name },
            mime = upload.mime.ifBlank { DEFAULT_MIME },
            size = upload.size,
            durationMs = upload.durationMs?.takeIf { it > 0 },
        )
        core.sendAttachment(upload.peer, upload.file.absolutePath, meta, upload.replyTo?.takeIf { it.isNotBlank() }, upload.isCovered)
    }

    /** A covered message behind a code or the device lock is revealed on the phone only. */
    override suspend fun attachment(messageId: String): DexFile? {
        val message = core.message(messageId) ?: return null
        val att = message.attachment ?: return null
        if (message.isDeleted || message.status.hasNoFile || message.status == MessageStatus.OFFERED || message.status == MessageStatus.RECEIVING) return null
        if (message.isCovered && !gateFor(message.peerIp).canRevealRemotely) return null
        val path = att.uri?.removePrefix("file://") ?: return null
        val file = File(path)
        if (!file.isFile) return null
        return DexFile(file, att.name, att.mime.ifBlank { DEFAULT_MIME })
    }

    override fun sendTyping(peer: String, isTyping: Boolean) {
        val contact = core.cachedContact(peer)
        val isOn = contact?.privacy?.sendTypingIndicators ?: prefs.prefs.value.sendTypingIndicators
        if (isOn) core.sendTyping(peer, isTyping)
    }

    override suspend fun markRead(peer: String) = core.markChatRead(peer)
    override suspend fun react(messageId: String, emoji: String) {
        core.reactToMessage(messageId, emoji)
        prefs.recordRecentReaction(emoji)
    }
    override suspend fun edit(messageId: String, body: String) = core.editMessage(messageId, body.trim().also { require(it.isNotEmpty()) { "Nothing to save" } })
    override suspend fun delete(messageId: String, forEveryone: Boolean) = if (forEveryone) core.deleteForEveryone(messageId) else core.deleteForMe(messageId)
    override suspend fun retryAction(actionId: String) = core.retryActionNow(actionId)
    override suspend fun cancelAction(actionId: String) = core.cancelAction(actionId)
    override suspend fun acceptOffer(messageId: String) = core.acceptAttachmentOffer(messageId, withContext(io) { files.freeBytes() })
    override suspend fun declineOffer(messageId: String) = core.declineAttachmentOffer(messageId)
    override suspend fun cancelTransfer(messageId: String) = core.cancelIncomingTransfer(messageId)

    override val callState: StateFlow<DexCallState> get() = calls.callState
    override val callEvents: Flow<DexCallEvent> get() = calls.callEvents
    override fun onCallCommand(command: DexCallCommand) = calls.onCommand(command)

    // --- settings --------------------------------------------------------------------------------

    override fun settings(): Flow<DexSettings> = prefs.prefs.map { it.toSettings() }.distinctUntilChanged()

    override suspend fun applySettings(patch: DexSettingsPatch) {
        val nextLogLevel = patch.nebulaLogLevel?.toCore()
        val isLogLevelChanged = nextLogLevel != null && nextLogLevel != prefs.prefs.value.nebulaLogLevel
        prefs.update { p ->
            p.copy(
                themeMode = patch.themeMode?.toCore() ?: p.themeMode,
                colorTheme = patch.colorTheme?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_THEME_CHARS } ?: p.colorTheme,
                customAccent = patch.customAccent?.takeIf { ACCENT.matches(it) } ?: p.customAccent,
                chatTextSize = patch.chatTextSize?.toCore() ?: p.chatTextSize,
                messageDensity = patch.messageDensity?.toCore() ?: p.messageDensity,
                isEnterToSend = patch.isEnterToSend ?: p.isEnterToSend,
                isVideoSpeakerDefault = patch.isVideoSpeakerDefault ?: p.isVideoSpeakerDefault,
                isScreenshotBlocked = patch.isScreenshotBlocked ?: p.isScreenshotBlocked,
                isBackgroundConnectionEnabled = patch.isBackgroundConnectionEnabled ?: p.isBackgroundConnectionEnabled,
                isStartOnBootEnabled = patch.isStartOnBootEnabled ?: p.isStartOnBootEnabled,
                notifications = patch.notifications?.toCore() ?: p.notifications,
                sendReadReceipts = patch.sendReadReceipts ?: p.sendReadReceipts,
                sendTypingIndicators = patch.sendTypingIndicators ?: p.sendTypingIndicators,
                presence = patch.presence?.let { PresencePrefs(isShared = it.isShared, pauseMinutes = it.pauseMinutes, pausedUntil = it.pausedUntil) } ?: p.presence,
                updates = patch.isDailyUpdateCheckEnabled?.let { p.updates.copy(isDailyCheckEnabled = it) } ?: p.updates,
                nebulaLogLevel = nextLogLevel ?: p.nebulaLogLevel,
                isDeveloperMode = patch.isDeveloperMode ?: p.isDeveloperMode,
                coverRevealGate = patch.coverRevealGate?.toCore() ?: p.coverRevealGate,
                autoCleanOrphans = patch.autoCleanOrphans ?: p.autoCleanOrphans,
                appLockAfterSec = patch.appLockAfterSec?.coerceIn(0, MAX_LOCK_DELAY_SEC) ?: p.appLockAfterSec,
                quickReactions = patch.quickReactions?.takeIf { it.size == p.quickReactions.size && it.all(::isEmoji) } ?: p.quickReactions,
            )
        }
        // nebula reads its log level from the site config, which only a reload hands it
        if (isLogLevelChanged) profile.value?.let { runtime.reloadTunnelConfig(it) }
    }

    override suspend fun setQuickReaction(slot: Int, emoji: String) {
        require(slot in prefs.prefs.value.quickReactions.indices) { "No such reaction slot" }
        require(isEmoji(emoji)) { "Bad reaction" }
        prefs.setQuickReaction(slot, emoji)
    }

    // --- what the phone reports --------------------------------------------------------------------

    override fun account(): Flow<DexAccount> = ticks(ACCOUNT_POLL_MS).mapLatest {
        val p = profile.value
        val expiry = p?.let { CertInspector.expiryStatus(it.certNotAfter) }
        val lighthouse = p?.lighthouses?.firstOrNull()
        DexAccount(
            certName = p?.certName.orEmpty(),
            overlayIp = p?.overlayIp.orEmpty(),
            certFingerprint = p?.certFingerprint.orEmpty(),
            certNotAfter = p?.certNotAfter.orEmpty(),
            certStatus = expiry?.text.orEmpty(),
            networks = p?.networks.orEmpty(),
            listenPort = p?.listenPort ?: 0,
            msgPort = p?.msgPort ?: 0,
            mtu = p?.nebula?.tun?.mtu ?: 0,
            lighthouseIp = lighthouse?.nebulaIp.orEmpty(),
            lighthouseUnderlay = lighthouse?.underlay.orEmpty(),
            appVersion = appVersion,
            coreVersion = core.coreVersion(),
        )
    }.distinctUntilChanged()

    override fun network(): Flow<DexNetwork> = merge(ticks(NETWORK_POLL_MS), core.storeChanges(), runtime.tunnelRunning.map { }).mapLatest {
        val p = profile.value
        val lighthouseIps = p?.lighthouses?.map { it.nebulaIp }.orEmpty()
        val isRunning = runtime.tunnelRunning.value
        val stats = core.networkStats()
        val hostmap = if (isRunning) vpn.listHostmap(lighthouseIps) else emptyList()
        val labels = core.readContacts().associate { it.ip to ContactLabels.chatLabel(it) }
        DexNetwork(
            isTunnelOn = isRunning,
            tunnelUptimeMs = if (isRunning) vpn.uptimeMs() else 0,
            engineUptimeMs = stats.uptimeMs,
            connectedCount = stats.connectedPeers.size,
            bytesSent = stats.bytesSent,
            bytesReceived = stats.bytesReceived,
            pendingActions = stats.pendingActions,
            failedActions = stats.failedActions,
            pendingHandshakes = if (isRunning) vpn.listHostmap(lighthouseIps, pending = true).size else 0,
            lighthouseStatus = lighthouseStatus(isRunning, hostmap),
            peers = hostmap.filter { it.vpnIp != p?.overlayIp }.map { h ->
                DexPeerRow(
                    ip = h.vpnIp,
                    label = labels[h.vpnIp] ?: h.certName ?: h.vpnIp,
                    isConnected = h.vpnIp in stats.connectedPeers,
                    endpoint = h.currentRemote ?: h.remoteAddrs.firstOrNull() ?: "no endpoint yet",
                )
            },
        )
    }.distinctUntilChanged()

    override fun storage(): Flow<DexStorage> = merge(ticks(STORAGE_POLL_MS), core.storeChanges()).mapLatest {
        val s = core.storageStats()
        DexStorage(
            dbBytes = s.dbBytes,
            attachmentsBytes = s.attachmentsBytes,
            attachmentsCount = s.attachmentsCount,
            orphanBytes = s.orphanBytes,
            orphanCount = s.orphanCount,
            partialBytes = s.partialBytes,
            partialCount = s.partialCount,
            messages = s.messages,
            contacts = s.contacts,
            freeBytes = withContext(io) { files.freeBytes() },
        )
    }.distinctUntilChanged()

    override fun diagnostics(): Flow<DexDiagnostics> = merge(ticks(DIAGNOSTICS_POLL_MS), core.storeChanges()).mapLatest {
        val isRunning = runtime.tunnelRunning.value
        val lighthouseIps = profile.value?.lighthouses?.map { it.nebulaIp }.orEmpty()
        val stats = core.networkStats()
        DexDiagnostics(
            logTail = vpn.readLog(LOG_TAIL_BYTES),
            callTrail = callTrail(),
            queuedCount = stats.pendingActions,
            queuedPeers = peerQueues.queues.value.size,
            failedCount = stats.failedActions,
            lighthouseStatus = lighthouseStatus(isRunning, if (isRunning) vpn.listHostmap(lighthouseIps) else emptyList()),
        )
    }.distinctUntilChanged()

    override fun updates(): Flow<DexUpdates> = combine(prefs.prefs, updateMonitor.isUpdateAvailable, updateMonitor.lastError) { p, hasUpdate, error ->
        DexUpdates(
            appVersion = appVersion,
            latestVersion = p.updates.latestVersion,
            isUpdateAvailable = hasUpdate,
            isDailyCheckEnabled = p.updates.isDailyCheckEnabled,
            lastCheckedAt = p.updates.lastCheckedAt,
            lastError = error,
        )
    }.distinctUntilChanged()

    // --- contacts -----------------------------------------------------------------------------------

    override suspend fun contactDetail(peer: String): DexContactDetail? {
        val contact = core.contact(peer) ?: return null
        val stats = core.peerStats(peer)
        val isRunning = runtime.tunnelRunning.value
        val host = if (isRunning) vpn.hostInfo(peer, profile.value?.lighthouses?.map { it.nebulaIp }.orEmpty()) else null
        val queue = peerQueues.of(peer)
        val stamp = now()
        return DexContactDetail(
            contact = contact.toContact(),
            stats = DexPeerStats(
                messagesSent = stats.messagesSent,
                messagesReceived = stats.messagesReceived,
                mediaSent = stats.mediaSent,
                mediaReceived = stats.mediaReceived,
                bytesSent = stats.bytesSent,
                bytesReceived = stats.bytesReceived,
                firstMessageAt = stats.firstMessageAt,
                lastActivityAt = stats.lastActivityAt,
                pendingActions = stats.pendingActions,
                failedActions = stats.failedActions,
                isConnected = stats.isConnected,
            ),
            presence = (PresenceStore.fresh(presenceStore.of(peer), stamp) ?: PeerPresence.OFFLINE).toWire(),
            clientVersion = contact.clientVersion.orEmpty(),
            peerCertName = host?.certName.orEmpty(),
            peerCertFingerprint = host?.certFingerprint.orEmpty(),
            endpoint = host?.currentRemote ?: host?.remoteAddrs?.firstOrNull().orEmpty(),
            connectionStatus = when {
                !isRunning -> "Tunnel off"
                stats.isConnected -> "Connected (message link open)"
                host != null -> "Tunnel established"
                else -> "Not connected"
            },
            queued = queue?.queued ?: 0,
            failed = stats.failedActions,
            privacy = DexContactPrivacy(
                sendReadReceipts = contact.privacy.sendReadReceipts,
                sendTypingIndicators = contact.privacy.sendTypingIndicators,
                blockScreenshots = contact.privacy.blockScreenshots,
                revealGate = contact.privacy.revealGate?.toWire(),
            ),
            notifications = contact.notifications?.let {
                DexContactNotifications(it.useGlobal, it.messages, it.preview, it.sound, it.vibrate, it.popup, it.reactions, it.calls)
            },
            calls = core.callLogs(peer, CONTACT_CALL_LOGS).map { it.toCallLog(contact.let(ContactLabels::chatLabel)) },
        )
    }

    override suspend fun saveContact(peer: String, name: String, nickname: String, notes: String) {
        require(core.contact(peer) != null) { "No contact at $peer" }
        core.updateContactDetails(peer, name, nickname, notes)
    }

    override suspend fun addContact(peer: String, name: String, nickname: String, notes: String) {
        val ip = CoreClient.normalizeIp(peer)
        require(ContactLabels.isOverlayIp(ip)) { "That is not a nebula address" }
        require(ip != profile.value?.overlayIp) { "That is this phone's own address" }
        require(core.contact(ip) == null) { "That contact is already saved" }
        core.upsertContact(ip, name.ifEmpty { ip })
        if (nickname.isNotEmpty() || notes.isNotEmpty()) core.updateContactDetails(ip, name.ifEmpty { ip }, nickname, notes)
    }

    override suspend fun deleteContact(peer: String) = core.deleteContact(peer)

    override suspend fun setContactFlags(peer: String, flags: DexContactFlags) = core.setContactFlags(
        peer,
        ContactFlagsPatch(
            isPinned = flags.isPinned,
            isArchived = flags.isArchived,
            isBlocked = flags.isBlocked,
            muteUntil = flags.muteUntil,
            isMarkedUnread = flags.isMarkedUnread,
            disappearSeconds = flags.disappearSeconds?.coerceAtLeast(0),
        ),
    )

    override suspend fun setContactPrivacy(peer: String, privacy: DexContactPrivacy) = core.setContactPrivacy(
        peer,
        ContactPrivacyPrefs(
            sendReadReceipts = privacy.sendReadReceipts,
            sendTypingIndicators = privacy.sendTypingIndicators,
            blockScreenshots = privacy.blockScreenshots,
            revealGate = privacy.revealGate?.toCore(),
        ),
    )

    override suspend fun setContactNotifications(peer: String, prefs: DexContactNotifications?) = core.setContactNotifications(
        peer,
        prefs?.let {
            ContactNotificationPrefs(it.useGlobal, it.messages, it.preview, it.sound, it.vibrate, it.popup, it.reactions, it.calls)
        },
    )

    override suspend fun changeContactIp(peer: String, newIp: String) {
        val next = CoreClient.normalizeIp(newIp)
        require(ContactLabels.isOverlayIp(next)) { "That is not a nebula address" }
        require(next != profile.value?.overlayIp) { "That is this phone's own address" }
        core.changeContactIp(peer, next)
    }

    // --- history and storage ---------------------------------------------------------------------

    override suspend fun clearHistory(peer: String) {
        core.clearHistory(peer)
    }

    override suspend fun clearAllHistory() {
        core.clearAllHistory()
    }

    override suspend fun clearOrphans(): Long = core.clearOrphanAttachments()

    override suspend fun callLogs(peer: String?, limit: Int): List<DexCallLog> {
        val labels = core.readContacts().associate { it.ip to ContactLabels.chatLabel(it) }
        val logs = if (peer == null) core.allCallLogs(limit) else core.callLogs(peer, limit)
        return logs.map { it.toCallLog(labels[it.peerIp]) }
    }

    override suspend fun deleteCallLogs(ids: List<String>) = core.deleteCallLogs(ids)

    override suspend fun chatMedia(peer: String): List<DexMessage> =
        core.chatMedia(peer).map { it.toMessage(emptyList(), null, emptyMap(), emptySet(), null, null) }

    override suspend fun chatLinks(peer: String): List<DexChatLink> = core.chatLinks(peer).map { DexChatLink(it.messageId, it.url, it.ts) }

    override suspend fun search(peer: String, text: String): List<DexMessage> {
        val query = text.trim()
        require(query.isNotEmpty()) { "Nothing to search for" }
        return core.searchMessages(peer, query).map { it.toMessage(emptyList(), null, emptyMap(), emptySet(), null, null) }
    }

    override suspend fun forward(messageId: String, peer: String) {
        core.forwardMessage(messageId, peer)
    }

    // --- network ------------------------------------------------------------------------------------

    override suspend fun pingPeer(peer: String): DexPingResult {
        val rtt = core.pingPeer(peer)
        return DexPingResult(peer, rtt, if (rtt < 0) "No answer" else null)
    }

    override suspend fun retryFailed(peer: String) {
        core.retryFailedActions(peer)
        if (peer.isEmpty()) peerQueues.queues.value.keys.forEach { core.drainNow(it) } else core.drainNow(peer)
    }

    override suspend fun drain(peer: String) = core.drainNow(peer)

    override suspend fun checkUpdates() {
        updateMonitor.check()
    }

    /** The first start needs the system's VPN consent, and only an Activity on the phone can ask for it. */
    override suspend fun setTunnel(isOn: Boolean) {
        if (!isOn) {
            runtime.stopVpn()
            return
        }
        val p = profile.value ?: throw IllegalStateException("The phone is not set up yet")
        if (vpn.prepareIntent() != null) throw IllegalStateException("The tunnel must be switched on from the phone the first time")
        runtime.ensureVpn(p)
    }

    // --- mapping ---------------------------------------------------------------------------------

    private val DexRevealGate.canRevealRemotely: Boolean get() = this == DexRevealGate.TAP || this == DexRevealGate.ASK

    private fun gateFor(peer: String): DexRevealGate = gateOf(core.cachedContact(peer), prefs.prefs.value.coverRevealGate)

    private fun gateOf(contact: Contact?, fallback: CoverRevealGate): DexRevealGate {
        val gate = contact?.privacy?.revealGate ?: fallback
        val effective = if (gate == CoverRevealGate.DEVICE && !appLock.canUseDeviceAuth()) CoverRevealGate.ASK else gate
        return when (effective) {
            CoverRevealGate.TAP -> DexRevealGate.TAP
            CoverRevealGate.ASK -> DexRevealGate.ASK
            CoverRevealGate.CODE -> DexRevealGate.CODE
            CoverRevealGate.DEVICE -> DexRevealGate.DEVICE
        }
    }

    private fun Contact.toContact(): DexContact = DexContact(
        ip = ip,
        label = ContactLabels.chatLabel(this),
        name = name,
        nickname = nickname,
        notes = notes,
        isBlocked = isBlocked,
        isArchived = isArchived,
        isPinned = pinnedAt != null,
        muteUntil = muteUntil,
        addedAt = addedAt,
        lastSeenAt = lastSeenAt,
        revealGate = gateOf(this, prefs.prefs.value.coverRevealGate),
        disappearSeconds = disappearSeconds,
    )

    private fun ChatSummary.toChat(contact: Contact?): DexChat = DexChat(
        peer = ip,
        label = contact?.let(ContactLabels::chatLabel) ?: name.ifEmpty { ip },
        lastBody = lastBody,
        lastTs = lastTs,
        lastDir = lastDirection?.toWire(),
        lastStatus = lastStatus?.toWire(),
        lastSend = lastSendStatus?.let { sendStateOf(it, isDraining = false) },
        unread = unread,
        isPinned = pinnedAt != null,
        isArchived = isArchived,
        isBlocked = isBlocked,
        isMuted = ContactLabels.isMuted(muteUntil, now()),
        isMarkedUnread = isMarkedUnread,
    )

    private fun ChatView.toView(peer: String, progress: Map<String, Double>, queue: PeerQueueState?, gate: CoverRevealGate): DexChatView {
        val contact = this.contact ?: core.cachedContact(peer) ?: Contact(ip = peer, name = peer, addedAt = 0)
        val label = ContactLabels.chatLabel(contact)
        return DexChatView(
            peer = peer,
            contact = contact.toContact(),
            messages = messages.map { it.toMessage(actions[it.id].orEmpty(), replySources[it.replyToId], progress, cancelledByMe, queue, label) },
            hasMore = messages.size >= CoreClient.CHAT_HEAD_LIMIT,
            freeBytes = files.freeBytes(),
        )
    }

    private fun ChatMessage.toMessage(
        actions: List<MessageAction>,
        replied: ChatMessage?,
        progress: Map<String, Double>,
        cancelledByMe: Set<String>,
        queue: PeerQueueState?,
        peerLabel: String?,
    ): DexMessage {
        val send = actions.firstOrNull { it.type == MessageActionType.SEND && it.status != MessageActionStatus.SUCCESS && it.status != MessageActionStatus.CANCELLED }
        val running = ActionQueue.runningActionId(actions)
        val pct = running?.let { progress[it] }
        val att = attachment
        return DexMessage(
            id = id,
            peer = peerIp,
            dir = direction.toWire(),
            body = if (isDeleted) "" else body,
            ts = ts,
            status = status.toWire(),
            kind = kindOf(kind, att),
            att = att?.let {
                DexAttachment(
                    name = it.name,
                    mime = it.mime.ifBlank { DEFAULT_MIME },
                    size = it.size,
                    width = it.width,
                    height = it.height,
                    durationMs = it.durationMs,
                    hasFile = !status.hasNoFile && status != MessageStatus.OFFERED && status != MessageStatus.RECEIVING && !isDeleted && it.uri != null,
                )
            },
            isEdited = isEdited,
            isDeleted = isDeleted,
            reactions = reactions,
            replyTo = replied?.let { r ->
                DexReplyPreview(
                    id = r.id,
                    name = if (r.direction == MessageDirection.OUT) "You" else peerLabel ?: r.peerIp,
                    snippet = when {
                        r.isDeleted -> "Message deleted"
                        r.isCovered -> "Covered message"
                        else -> r.body.ifEmpty { r.attachment?.name ?: "Attachment" }
                    },
                )
            },
            seenAt = seenAt,
            isRead = isRead,
            expiresAt = expiresAt,
            isCovered = isCovered,
            isCancelledByMe = id in cancelledByMe,
            send = send?.let { sendStateOf(it.status, queue?.isDraining == true) },
            actionId = send?.id ?: running,
            transferPct = pct?.let { (it * 100).toInt().coerceIn(0, 100) },
        )
    }

    private fun kindOf(kind: MessageKind, att: MessageAttachment?): DexMessageKind = when {
        att != null && att.mime.startsWith("audio/") -> DexMessageKind.VOICE
        kind == MessageKind.IMAGE -> DexMessageKind.IMAGE
        kind == MessageKind.VIDEO -> DexMessageKind.VIDEO
        kind == MessageKind.FILE -> DexMessageKind.FILE
        else -> DexMessageKind.TEXT
    }

    private fun sendStateOf(status: MessageActionStatus, isDraining: Boolean): DexSendState? = when (status) {
        MessageActionStatus.PENDING -> if (isDraining) DexSendState.SENDING else DexSendState.QUEUED
        MessageActionStatus.WAITING -> DexSendState.WAITING
        MessageActionStatus.FAILED -> DexSendState.FAILED
        MessageActionStatus.SUCCESS, MessageActionStatus.CANCELLED -> null
    }

    private fun PeerQueueState.toQueue() = DexQueue(ip, queued, isReachable, isDraining)

    private fun MessageDirection.toWire() = if (this == MessageDirection.OUT) DexDirection.OUT else DexDirection.IN

    private fun MessageStatus.toWire(): DexMessageStatus = when (this) {
        MessageStatus.PENDING -> DexMessageStatus.PENDING
        MessageStatus.SENT -> DexMessageStatus.SENT
        MessageStatus.DELIVERED -> DexMessageStatus.DELIVERED
        MessageStatus.OFFERED -> DexMessageStatus.OFFERED
        MessageStatus.DECLINED -> DexMessageStatus.DECLINED
        MessageStatus.CANCELLED -> DexMessageStatus.CANCELLED
        MessageStatus.RECEIVING -> DexMessageStatus.RECEIVING
        MessageStatus.RECEIVED -> DexMessageStatus.RECEIVED
    }

    private fun PeerPresence.toWire(): DexPresence = when (this) {
        PeerPresence.ONLINE -> DexPresence.ONLINE
        PeerPresence.REACHABLE -> DexPresence.REACHABLE
        PeerPresence.OFFLINE -> DexPresence.OFFLINE
    }

    private fun CallLog.toCallLog(label: String?): DexCallLog = DexCallLog(
        id = id,
        peer = peerIp,
        label = label ?: peerIp,
        dir = direction.toWire(),
        isVideo = isVideo,
        outcome = when (outcome) {
            CoreCallOutcome.ANSWERED -> DexCallOutcome.ANSWERED
            CoreCallOutcome.MISSED -> DexCallOutcome.MISSED
            CoreCallOutcome.DECLINED -> DexCallOutcome.DECLINED
            CoreCallOutcome.NO_ANSWER -> DexCallOutcome.NO_ANSWER
            CoreCallOutcome.UNREACHABLE -> DexCallOutcome.UNREACHABLE
            CoreCallOutcome.CANCELLED -> DexCallOutcome.CANCELLED
            CoreCallOutcome.FAILED -> DexCallOutcome.FAILED
        },
        startedAt = startedAt,
        connectedAt = connectedAt,
        endedAt = endedAt,
    )

    // --- settings mapping --------------------------------------------------------------------------

    private fun Prefs.toSettings(): DexSettings = DexSettings(
        themeMode = when (themeMode) {
            ThemeMode.SYSTEM -> DexThemeMode.SYSTEM
            ThemeMode.LIGHT -> DexThemeMode.LIGHT
            ThemeMode.DARK -> DexThemeMode.DARK
        },
        colorTheme = colorTheme,
        customAccent = customAccent,
        chatTextSize = when (chatTextSize) {
            ChatTextSize.SMALL -> DexTextSize.SMALL
            ChatTextSize.MEDIUM -> DexTextSize.MEDIUM
            ChatTextSize.LARGE -> DexTextSize.LARGE
        },
        messageDensity = when (messageDensity) {
            MessageDensity.COMFORTABLE -> DexDensity.COMFORTABLE
            MessageDensity.COMPACT -> DexDensity.COMPACT
        },
        isEnterToSend = isEnterToSend,
        isVideoSpeakerDefault = isVideoSpeakerDefault,
        isScreenshotBlocked = isScreenshotBlocked,
        isBackgroundConnectionEnabled = isBackgroundConnectionEnabled,
        isStartOnBootEnabled = isStartOnBootEnabled,
        notifications = DexNotifications(
            messages = notifications.messages.let {
                DexMessageNotifications(it.enabled, it.showSender, it.preview, it.sound, it.vibrate, it.popup, it.reactions)
            },
            calls = notifications.calls.let { DexCallNotifications(it.ring, it.vibrate, it.missedNotification) },
            inApp = DexInAppNotifications(notifications.inApp.vibrate),
            quietHours = notifications.quietHours.let {
                DexQuietHours(it.enabled, it.fromHour, it.fromMinute, it.toHour, it.toMinute)
            },
        ),
        sendReadReceipts = sendReadReceipts,
        sendTypingIndicators = sendTypingIndicators,
        presence = DexPresencePrefs(presence.isShared, presence.pauseMinutes, presence.pausedUntil),
        updates = DexUpdatePrefs(updates.isDailyCheckEnabled, updates.lastCheckedAt, updates.latestVersion),
        nebulaLogLevel = if (nebulaLogLevel == NebulaLogLevel.DEBUG) DexLogLevel.DEBUG else DexLogLevel.INFO,
        isDeveloperMode = isDeveloperMode,
        coverRevealGate = coverRevealGate.toWire(),
        autoCleanOrphans = autoCleanOrphans,
        quickReactions = quickReactions,
        recentReactions = recentReactions,
        isAppLockEnabled = isAppLockEnabled,
        appLockAfterSec = appLockAfterSec,
        dexUsername = dex.username,
        dexMaxClients = dex.maxClients,
        dexPort = dex.port,
    )

    private fun DexNotifications.toCore(): NotificationPrefs = NotificationPrefs(
        messages = MessageNotificationPrefs(messages.enabled, messages.showSender, messages.preview, messages.sound, messages.vibrate, messages.popup, messages.reactions),
        calls = CallNotificationPrefs(calls.ring, calls.vibrate, calls.missedNotification),
        inApp = InAppNotificationPrefs(inApp.vibrate),
        quietHours = QuietHours(
            enabled = quietHours.enabled,
            fromHour = quietHours.fromHour.coerceIn(0, 23),
            fromMinute = quietHours.fromMinute.coerceIn(0, 59),
            toHour = quietHours.toHour.coerceIn(0, 23),
            toMinute = quietHours.toMinute.coerceIn(0, 59),
        ),
    )

    private fun DexThemeMode.toCore(): ThemeMode = when (this) {
        DexThemeMode.SYSTEM -> ThemeMode.SYSTEM
        DexThemeMode.LIGHT -> ThemeMode.LIGHT
        DexThemeMode.DARK -> ThemeMode.DARK
    }

    private fun DexTextSize.toCore(): ChatTextSize = when (this) {
        DexTextSize.SMALL -> ChatTextSize.SMALL
        DexTextSize.MEDIUM -> ChatTextSize.MEDIUM
        DexTextSize.LARGE -> ChatTextSize.LARGE
    }

    private fun DexDensity.toCore(): MessageDensity = when (this) {
        DexDensity.COMFORTABLE -> MessageDensity.COMFORTABLE
        DexDensity.COMPACT -> MessageDensity.COMPACT
    }

    private fun DexLogLevel.toCore(): NebulaLogLevel = if (this == DexLogLevel.DEBUG) NebulaLogLevel.DEBUG else NebulaLogLevel.INFO

    private fun DexRevealGate.toCore(): CoverRevealGate = when (this) {
        DexRevealGate.TAP -> CoverRevealGate.TAP
        DexRevealGate.ASK -> CoverRevealGate.ASK
        DexRevealGate.CODE -> CoverRevealGate.CODE
        DexRevealGate.DEVICE -> CoverRevealGate.DEVICE
    }

    private fun CoverRevealGate.toWire(): DexRevealGate = when (this) {
        CoverRevealGate.TAP -> DexRevealGate.TAP
        CoverRevealGate.ASK -> DexRevealGate.ASK
        CoverRevealGate.CODE -> DexRevealGate.CODE
        CoverRevealGate.DEVICE -> DexRevealGate.DEVICE
    }

    private fun lighthouseStatus(isRunning: Boolean, hostmap: List<HostmapEntry>): String = when {
        !isRunning -> "Tunnel off"
        hostmap.any { it.isLighthouse } -> "Reachable (tunnel established)"
        else -> "Not reached"
    }

    private fun isEmoji(value: String): Boolean = value.isNotEmpty() && value.length <= MAX_EMOJI_CHARS

    /** Emits at once and then on a cadence, only while something collects it. */
    private fun ticks(everyMs: Long): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(everyMs)
        }
    }

    private companion object {
        const val DEFAULT_MIME = "application/octet-stream"
        const val MAX_EMOJI_CHARS = 16
        const val MAX_THEME_CHARS = 32
        const val MAX_LOCK_DELAY_SEC = 60 * 60
        const val CONTACT_CALL_LOGS = 30
        const val LOG_TAIL_BYTES = 24 * 1024
        /** the certificate and the ports change only at setup, so this is a long safety net */
        const val ACCOUNT_POLL_MS = 60_000L
        const val NETWORK_POLL_MS = 5_000L
        const val STORAGE_POLL_MS = 30_000L
        const val DIAGNOSTICS_POLL_MS = 10_000L
        val ACCENT = Regex("#[0-9a-fA-F]{6}")
    }
}

package com.telenebula.app.runtime

import com.telenebula.app.platform.AppLock
import com.telenebula.app.platform.AttachmentStore
import com.telenebula.app.platform.ActionQueue
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.core.CoreClient
import com.telenebula.core.PeerQueueStore
import com.telenebula.core.PresenceStore
import com.telenebula.core.TransferProgressStore
import com.telenebula.core.TypingStore
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.ChatSummary
import com.telenebula.core.model.ChatView
import com.telenebula.core.model.Contact
import com.telenebula.core.model.CoverRevealGate
import com.telenebula.core.model.MessageAction
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
import com.telenebula.dex.wire.DexAttachment
import com.telenebula.dex.wire.DexCallState
import com.telenebula.dex.wire.DexChat
import com.telenebula.dex.wire.DexChatView
import com.telenebula.dex.wire.DexContact
import com.telenebula.dex.wire.DexDirection
import com.telenebula.dex.wire.DexIdentity
import com.telenebula.dex.wire.DexMessage
import com.telenebula.dex.wire.DexMessageKind
import com.telenebula.dex.wire.DexMessageStatus
import com.telenebula.dex.wire.DexPresence
import com.telenebula.dex.wire.DexQueue
import com.telenebula.dex.wire.DexReplyPreview
import com.telenebula.dex.wire.DexRevealGate
import com.telenebula.dex.wire.DexSendState
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * The Dex server's view of this phone: every read is one of the core's coarse reactive views
 * mapped to the wire's types, every command is the same core call the phone's own screens make.
 */
class DexBackendAdapter(
    scope: CoroutineScope,
    profile: StateFlow<Profile?>,
    private val core: CoreClient,
    private val prefs: PrefsRepository,
    private val typingStore: TypingStore,
    private val presenceStore: PresenceStore,
    private val transfers: TransferProgressStore,
    private val peerQueues: PeerQueueStore,
    private val files: AttachmentStore,
    private val appLock: AppLock,
    private val calls: DexCallBridge,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : DexBackend {
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
        isBlocked = isBlocked,
        isArchived = isArchived,
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

    private companion object {
        const val DEFAULT_MIME = "application/octet-stream"
    }
}

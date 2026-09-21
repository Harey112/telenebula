package com.telenebula.core

import com.telenebula.core.backup.BackupService
import com.telenebula.core.db.Store
import com.telenebula.core.db.Wire
import com.telenebula.core.db.Wire.wire
import com.telenebula.core.engine.Engine
import com.telenebula.core.engine.EngineProfile
import com.telenebula.core.engine.Limits
import com.telenebula.core.engine.Transport
import com.telenebula.core.events.CoreEvent
import com.telenebula.core.events.CoreEventBus
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
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageAttachment
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.NetworkStats
import com.telenebula.core.model.OutboundSignal
import com.telenebula.core.model.PeerQueueState
import com.telenebula.core.model.PeerStats
import com.telenebula.core.model.StorageStats
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The core, as one object for the whole process. It owns the store — opened once and kept open for
 * queries — and at most one [Engine], created by [start] and torn down completely by [stop].
 */
internal class TnCore(
    private val paths: CorePaths,
    private val sink: (CoreEvent) -> Unit = CoreEventBus::emit,
    /** how the store is opened; the tests pass a JDBC-backed one */
    private val openStore: (String) -> Store = Store::open,
) : MessagingCore {
    private val lifecycle = ReentrantLock()

    @Volatile
    private var store: Store? = null

    @Volatile
    private var engine: Engine? = null

    // the app reports the tunnel before the engine exists on a cold start; the value must outlive that gap
    private val isTunnelUp = AtomicBoolean(true)
    private val isOnline = AtomicBoolean(false)

    override val version: String get() = CORE_VERSION

    override fun openStore() {
        lifecycle.withLock {
            if (store != null) return
            File(paths.dbPath).parentFile?.mkdirs()
            File(paths.attachmentsDir).mkdirs()
            store = openStore(paths.dbPath)
            CoreRegistry.register(this)
        }
    }

    override fun start(config: CoreStartConfig) {
        lifecycle.withLock {
            if (engine != null) return
            openStore()
            val store = requireStore()
            store.backfillSendActions()
            engine = Engine(
                store = store,
                profile = EngineProfile(
                    overlayIp = Ip.normalize(config.overlayIp),
                    displayName = config.displayName,
                    msgPort = config.msgPort,
                    appVersion = config.appVersion,
                ),
                attachmentsDir = File(paths.attachmentsDir),
                sendReadReceipts = config.sendReadReceipts,
                sink = sink,
                isTunnelUp = isTunnelUp.get(),
                isOnline = isOnline.get(),
            ).also { it.start() }
        }
    }

    override fun stop() = lifecycle.withLock {
        engine?.stop()
        engine = null
    }

    /**
     * The overlay came up or went away. Only the scheduler cares: while the tunnel is down nothing
     * is probed, because a peer that cannot be reached through a tunnel that is not there has done
     * nothing wrong and must not climb anybody's backoff ladder.
     */
    override fun setTunnelState(running: Boolean) {
        isTunnelUp.set(running)
        engine?.onTunnelState(running)
    }

    override fun setOnline(isOnline: Boolean) {
        this.isOnline.set(isOnline)
        engine?.isOnline?.set(isOnline)
    }

    override fun setSendReadReceipts(app: Boolean, anyProfile: Boolean) {
        engine?.sendReadReceipts?.set(app)
        engine?.anyProfileSendsReadReceipts?.set(app || anyProfile)
    }

    // --- queries ---

    override fun chatView(peerIp: String, limit: Int): ChatView =
        requireStore().getChatView(Ip.normalize(peerIp), limit.coerceIn(1, MAX_PAGE).toLong())

    override fun chatViewFrom(peerIp: String, from: MessageCursor, limit: Int): ChatView =
        requireStore().getChatView(Ip.normalize(peerIp), from, limit.coerceIn(1, MAX_PAGE).toLong())

    override fun messagesBefore(peerIp: String, before: MessageCursor, limit: Int): List<ChatMessage> =
        requireStore().getMessagesBefore(Ip.normalize(peerIp), before, limit.coerceIn(1, MAX_PAGE).toLong())

    override fun chatRows(peerIp: String, ids: Collection<String>): ChatView =
        requireStore().getChatRows(Ip.normalize(peerIp), ids.take(MAX_PAGE))

    override fun chatSummaries(): List<ChatSummary> = requireStore().getChatSummaries()

    override fun contacts(): List<Contact> = requireStore().getContacts()

    override fun contact(ip: String): Contact? = requireStore().getContact(Ip.normalize(ip))

    override fun message(id: String): ChatMessage? = requireStore().getMessage(id)

    override fun messageActions(messageId: String): List<MessageAction> =
        requireStore().getActionsForMessage(messageId)

    override fun searchMessages(peerIp: String, query: String, limit: Int): List<ChatMessage> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        return requireStore().searchMessages(Ip.normalize(peerIp), text, limit.coerceIn(1, MAX_LIST).toLong())
    }

    override fun exportChatText(peerIp: String, peerLabel: String): String {
        val ip = Ip.normalize(peerIp)
        val messages = requireStore().allMessages(ip, EXPORT_CAP)
        return buildString {
            append("Chat with $peerLabel ($ip)\n\n")
            for (message in messages) {
                val who = if (message.direction == MessageDirection.OUT) "Me" else peerLabel
                val attachment = message.attachment
                val body = when {
                    message.isDeleted -> "[deleted]"
                    attachment != null -> {
                        val label = "[${message.kind.wire}: ${attachment.name}]"
                        if (message.body.isEmpty()) label else "$label ${message.body}"
                    }
                    else -> message.body
                }
                append(message.ts).append('\t').append(who).append('\t')
                append(body.replace('\n', ' ')).append('\n')
            }
        }
    }

    override fun allCallLogs(limit: Int): List<CallLog> = requireStore().getAllCallLogs(limit.toLong())

    override fun callLogs(peerIp: String, limit: Int): List<CallLog> =
        requireStore().getCallLogs(Ip.normalize(peerIp), limit.coerceIn(1, MAX_CALL_LOGS).toLong())

    override fun chatMedia(peerIp: String, limit: Int): List<ChatMessage> =
        requireStore().getChatMedia(Ip.normalize(peerIp), limit.toLong())

    override fun chatLinks(peerIp: String, limit: Int): List<ChatLink> =
        requireStore().getChatLinks(Ip.normalize(peerIp), limit.toLong())

    override fun chatMediaCount(peerIp: String): Int = requireStore().countChatMedia(Ip.normalize(peerIp))

    override fun chatLinkMessageCount(peerIp: String): Int = requireStore().countChatLinkMessages(Ip.normalize(peerIp))

    override fun unreadTotal(): Int = requireStore().getUnreadTotal()

    override fun peerStats(peerIp: String): PeerStats {
        val ip = Ip.normalize(peerIp)
        val store = requireStore()
        val running = engine ?: return store.peerStats(ip)
        running.flushTraffic()
        return store.peerStats(ip).copy(isConnected = ip in running.transport.connectedPeers())
    }

    override fun networkStats(): NetworkStats {
        val store = requireStore()
        val running = engine
        running?.flushTraffic()
        val (sent, received) = store.totalTraffic()
        val (pending, failed) = store.actionCounts(null)
        return NetworkStats(
            bytesSent = sent,
            bytesReceived = received,
            connectedPeers = running?.transport?.connectedPeers().orEmpty(),
            pendingActions = pending,
            failedActions = failed,
            uptimeMs = running?.let { System.currentTimeMillis() - it.startedAtMs } ?: 0,
        )
    }

    override fun storageStats(): StorageStats {
        val store = requireStore()
        var dbBytes = 0L
        for (suffix in DB_PARTS) {
            val file = File(paths.dbPath + suffix)
            if (file.isFile) dbBytes += file.length()
        }
        var attachmentsBytes = 0L
        var attachmentsCount = 0
        for (file in File(paths.attachmentsDir).listFiles().orEmpty()) {
            if (!file.isFile) continue
            attachmentsBytes += file.length()
            attachmentsCount += 1
        }
        // the same scan the sweep runs, so the count shown is exactly what "remove" will delete
        val scan = backups().scanAttachments()
        val (messages, contacts) = store.tableCounts()
        return StorageStats(
            dbBytes = dbBytes,
            attachmentsBytes = attachmentsBytes,
            attachmentsCount = attachmentsCount,
            orphanBytes = scan.orphans.sumOf { it.length() },
            orphanCount = scan.orphans.size,
            partialBytes = scan.partialBytes,
            partialCount = scan.partialCount,
            messages = messages,
            contacts = contacts,
        )
    }

    // --- commands ---

    override fun sendText(peerIp: String, body: String, replyToId: String?, isCovered: Boolean) =
        requireEngine().outbox.sendText(peerIp, body, replyToId, isCovered)

    override fun sendAttachment(peerIp: String, path: String, meta: MessageAttachment, replyToId: String?, isCovered: Boolean) =
        requireEngine().outbox.sendAttachment(peerIp, path, meta, replyToId, isCovered)

    override fun reactToMessage(messageId: String, emoji: String) =
        requireEngine().outbox.reactToMessage(messageId, emoji)

    override fun editMessage(messageId: String, newBody: String) =
        requireEngine().outbox.editMessage(messageId, newBody)

    override fun deleteForEveryone(messageId: String) = requireEngine().outbox.deleteForEveryone(messageId)

    override fun deleteForMe(messageId: String) = requireStore().deleteMessageForMe(messageId)

    override fun forwardMessage(sourceMessageId: String, targetPeerIp: String) =
        requireEngine().outbox.forwardMessage(sourceMessageId, targetPeerIp)

    override fun retryAction(actionId: String) = requireEngine().outbox.retryAction(actionId)

    override fun retryActionNow(actionId: String) = requireEngine().outbox.retryActionNow(actionId)

    override fun acceptAttachmentOffer(transferId: String, freeBytes: Long) =
        requireEngine().attachments.answerOffer(transferId, accept = true, freeBytes = freeBytes)

    override fun declineAttachmentOffer(transferId: String) =
        requireEngine().attachments.answerOffer(transferId, accept = false, freeBytes = 0)

    override fun cancelIncomingTransfer(transferId: String) =
        requireEngine().attachments.cancelIncoming(transferId)

    override fun cancelAction(actionId: String) = requireEngine().outbox.cancelAction(actionId)

    override fun retryFailedActions(peerIp: String): Int {
        val ip = Ip.normalize(peerIp)
        return requireEngine().outbox.retryFailed(ip.takeIf { it.isNotEmpty() })
    }

    override fun queuedActionCount(peerIp: String): Int {
        val ip = Ip.normalize(peerIp)
        return requireStore().openActionCount(ip)
    }

    /** Marks the chat read and, when the engine runs, reports the newly read messages as seen. */
    override fun markChatRead(peerIp: String, surfaceSendsReceipts: Boolean?) {
        val ip = Ip.normalize(peerIp)
        requireStore().markChatRead(ip)
        // announced here, not by the receipt: a manual unread mark or receipts turned off queue none
        engine?.events?.chatChanged(ip)
        engine?.outbox?.reportSeen(ip, surfaceSendsReceipts ?: engine?.sendReadReceipts?.get() ?: false)
    }

    override fun clearHistory(peerIp: String) = requireStore().clearMessages(Ip.normalize(peerIp))

    override fun clearAllHistory() {
        requireStore().clearAllMessages()
        engine?.events?.summariesChanged()
    }

    override fun logCall(log: CallLog) {
        val normalized = log.copy(peerIp = Ip.normalize(log.peerIp))
        requireStore().insertCallLog(normalized)
        engine?.events?.let {
            it.chatChanged(normalized.peerIp)
            it.callLogsChanged()
        }
    }

    override fun deleteCallLogs(ids: List<String>) {
        if (ids.isEmpty()) return
        requireStore().deleteCallLogs(ids)
        engine?.events?.callLogsChanged()
    }

    // --- contacts ---

    override fun upsertContact(ip: String, name: String) = requireStore().upsertContact(Ip.normalize(ip), name)

    override fun ensureContact(ip: String, name: String) {
        requireStore().syncContact(Ip.normalize(ip), name)
    }

    override fun updateContactDetails(ip: String, name: String, nickname: String, notes: String) =
        requireStore().updateContactDetails(Ip.normalize(ip), name, nickname, notes)

    override fun deleteContact(ip: String) {
        val normalized = Ip.normalize(ip)
        requireStore().deleteContact(normalized)
        // its queue went with it; without this the scheduler keeps a schedule for an address that
        // has no rows and no contact behind it any more
        engine?.delivery?.forget(normalized)
    }

    /** Moves a contact and its history to a new address, merging with any contact already there. */
    override fun changeContactIp(oldIp: String, newIp: String) {
        val from = Ip.normalize(oldIp)
        val to = Ip.normalize(newIp)
        if (to.isEmpty()) throw CoreException.internal("new address is empty")
        if (from == to) return
        requireStore().changeContactIp(from, to)
        // the queue moved with the contact: drop the old address's connection and schedule, and
        // put the new one straight in front of the scheduler rather than a probe cycle behind
        engine?.transport?.evict(from)
        engine?.delivery?.forget(from)
        engine?.delivery?.kick(to)
        engine?.events?.let {
            it.chatChanged(from)
            it.chatChanged(to)
        }
    }

    override fun setContactFlags(ip: String, patch: ContactFlagsPatch) {
        val normalized = Ip.normalize(ip)
        requireStore().setContactFlags(normalized, patch)
        // a blocked peer is filtered out of the scheduler's read, so blocking stops the probing by
        // itself; unblocking has to say so, or the queue would wait out the scheduler's long sleep
        patch.isBlocked?.let { if (it) engine?.delivery?.forget(normalized) else engine?.delivery?.kick(normalized) }
        engine?.events?.contactChanged(normalized)
    }

    override fun setContactNotifications(ip: String, prefs: ContactNotificationPrefs?) {
        val normalized = Ip.normalize(ip)
        requireStore().setContactNotifications(normalized, prefs)
        engine?.events?.contactChanged(normalized)
    }

    override fun setContactPrivacy(ip: String, prefs: ContactPrivacyPrefs) {
        val normalized = Ip.normalize(ip)
        requireStore().setContactPrivacy(normalized, prefs)
        engine?.events?.contactChanged(normalized)
    }

    // --- storage and backups ---

    override fun clearOrphanAttachments(): Long = backups().clearOrphanAttachments()

    override fun createBackup(prefsPath: String, destinationPath: String, appVersion: String): BackupSummary =
        backups().create(prefsPath, destinationPath, appVersion)

    override fun importBackup(archivePath: String, prefsPath: String): BackupSummary =
        backups().import(archivePath, prefsPath).also { engine?.events?.summariesChanged() }

    // --- network waits ---

    /**
     * A ping the user asked for. It does not merely report a round trip: an answer is proof the
     * peer is there, so whatever is queued for it goes **now** instead of waiting out the rung its
     * silence had climbed to. That is the point of tapping Ping on a chat that will not send.
     */
    override suspend fun pingPeer(peerIp: String, timeoutMs: Int): Long {
        val running = engine ?: return Transport.UNREACHABLE
        return running.pingPeer(peerIp, timeoutMs.toLong().takeIf { it > 0 })
    }

    /** Probe this peer now and move whatever it is holding, whatever the schedule said. */
    override fun drainNow(peerIp: String) {
        engine?.delivery?.kick(Ip.normalize(peerIp))
    }

    override fun peerQueue(peerIp: String): PeerQueueState {
        val ip = Ip.normalize(peerIp)
        val live = engine?.delivery?.stateOf(ip)
        if (live != null) return live
        return PeerQueueState(ip = ip, queued = requireStore().openActionCount(ip), isTunnelUp = engine?.isTunnelUp?.get() ?: false)
    }

    override suspend fun sendSignal(peerIp: String, signal: OutboundSignal, timeoutMs: Int) {
        requireEngine().transport.sendSignal(peerIp, signal, timeoutMs.toLong().takeIf { it > 0 })
    }

    // --- internals ---

    private fun requireStore(): Store = store ?: throw CoreException.notRunning()

    private fun requireEngine(): Engine = engine ?: throw CoreException.notRunning()

    private fun backups(): BackupService = BackupService(requireStore(), paths)

    companion object {
        /** Shown on the About screen and in a diagnostics report: the wire version and who speaks it. */
        const val CORE_VERSION = "1.0.0-kotlin"

        private const val MAX_PAGE = 1000
        private const val MAX_LIST = 500
        private const val MAX_CALL_LOGS = 500
        private const val EXPORT_CAP = 5000L
        private val DB_PARTS = listOf("", "-wal", "-shm")
    }
}

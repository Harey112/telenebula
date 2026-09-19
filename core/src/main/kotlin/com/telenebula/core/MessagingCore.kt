package com.telenebula.core

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
import com.telenebula.core.model.NetworkStats
import com.telenebula.core.model.OutboundSignal
import com.telenebula.core.model.PeerQueueState
import com.telenebula.core.model.PeerStats
import com.telenebula.core.model.StorageStats

/**
 * The messaging domain: storage, protocol, transport, outbox and transfers, behind one interface
 * so the client can be tested against a fake. Everything is typed — no JSON crosses this line —
 * and every member here is synchronous and blocking except the two that wait on the network; the
 * client keeps them off the main thread.
 */
internal interface MessagingCore {
    val version: String

    /** Opens the store (running the migrations) so queries work before the engine is started. */
    fun openStore()

    /** Starts the engine: listener, outbox, event pump. A running engine is left untouched. */
    fun start(config: CoreStartConfig)

    /** Stops the engine and every task it owns. The store stays open for queries. */
    fun stop()

    /** The overlay came up or went away; while it is down nothing is probed. */
    fun setTunnelState(running: Boolean)

    /** Privacy switch: whether read receipts are delivered to peers. */
    fun setSendReadReceipts(enabled: Boolean)

    /** Whether our pong says "online": the app is in use and the user shares that. */
    fun setOnline(isOnline: Boolean)

    // --- queries ---

    fun chatView(peerIp: String, limit: Int): ChatView

    /** The oldest [limit] messages at or after [from], with their actions and reply sources. */
    fun chatViewFrom(peerIp: String, from: MessageCursor, limit: Int): ChatView

    /** The [limit] messages just before [before], ascending; a short page means the beginning. */
    fun messagesBefore(peerIp: String, before: MessageCursor, limit: Int): List<ChatMessage>

    /** The named rows with their actions and reply sources, plus the contact; what a targeted re-read patches in. */
    fun chatRows(peerIp: String, ids: Collection<String>): ChatView

    fun chatSummaries(): List<ChatSummary>

    fun contacts(): List<Contact>

    fun contact(ip: String): Contact?

    fun message(id: String): ChatMessage?

    fun messageActions(messageId: String): List<MessageAction>

    fun searchMessages(peerIp: String, query: String, limit: Int): List<ChatMessage>

    fun exportChatText(peerIp: String, peerLabel: String): String

    fun allCallLogs(limit: Int): List<CallLog>

    fun callLogs(peerIp: String, limit: Int): List<CallLog>

    fun chatMedia(peerIp: String, limit: Int): List<ChatMessage>

    fun chatLinks(peerIp: String, limit: Int): List<ChatLink>

    /** How many messages [chatMedia] would return unbounded. */
    fun chatMediaCount(peerIp: String): Int

    /** How many messages in the chat carry a URL. */
    fun chatLinkMessageCount(peerIp: String): Int

    fun unreadTotal(): Int

    fun peerStats(peerIp: String): PeerStats

    fun networkStats(): NetworkStats

    fun storageStats(): StorageStats

    // --- commands ---

    fun sendText(peerIp: String, body: String, replyToId: String?, isCovered: Boolean)

    /** The bytes stay at [path]; only the path is handed over. */
    fun sendAttachment(peerIp: String, path: String, meta: MessageAttachment, replyToId: String?, isCovered: Boolean)

    fun reactToMessage(messageId: String, emoji: String)

    fun editMessage(messageId: String, newBody: String)

    fun deleteForEveryone(messageId: String)

    fun deleteForMe(messageId: String)

    fun forwardMessage(sourceMessageId: String, targetPeerIp: String)

    fun retryAction(actionId: String)

    fun retryActionNow(actionId: String)

    /** [freeBytes] lets the core refuse an offer that would not fit rather than start it. */
    fun acceptAttachmentOffer(transferId: String, freeBytes: Long)

    fun declineAttachmentOffer(transferId: String)

    /** Abandons an incoming transfer the person no longer wants; the sender is told. */
    fun cancelIncomingTransfer(transferId: String)

    fun cancelAction(actionId: String)

    /** Re-queues failed actions; an empty peer means everywhere. Returns how many. */
    fun retryFailedActions(peerIp: String): Int

    /** How many actions are still waiting to reach this peer. */
    fun queuedActionCount(peerIp: String): Int

    /** Probe this peer now and deliver whatever is queued, rather than waiting for its next probe. */
    fun drainNow(peerIp: String)

    /** What this peer's queue is doing right now, for a screen opening before any event arrives. */
    fun peerQueue(peerIp: String): PeerQueueState

    fun markChatRead(peerIp: String)

    fun clearHistory(peerIp: String)

    fun clearAllHistory()

    fun logCall(log: CallLog)

    fun deleteCallLogs(ids: List<String>)

    // --- contacts ---

    fun upsertContact(ip: String, name: String)

    /** Adds the contact when unknown and syncs the username the peer announces. */
    fun ensureContact(ip: String, name: String)

    fun updateContactDetails(ip: String, name: String, nickname: String, notes: String)

    fun deleteContact(ip: String)

    fun changeContactIp(oldIp: String, newIp: String)

    fun setContactFlags(ip: String, patch: ContactFlagsPatch)

    fun setContactNotifications(ip: String, prefs: ContactNotificationPrefs?)

    fun setContactPrivacy(ip: String, prefs: ContactPrivacyPrefs)

    // --- storage and backups ---

    fun clearOrphanAttachments(): Long

    fun createBackup(prefsPath: String, destinationPath: String, appVersion: String): BackupSummary

    fun importBackup(archivePath: String, prefsPath: String): BackupSummary

    // --- network waits ---

    /** Round-trip in ms, or -1 when the peer did not answer within [timeoutMs] (0 = the default). */
    suspend fun pingPeer(peerIp: String, timeoutMs: Int): Long

    /** Delivers one signalling envelope; throws when the peer is unreachable. */
    suspend fun sendSignal(peerIp: String, signal: OutboundSignal, timeoutMs: Int)
}

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

/** Every member answers with an empty value; a test overrides only what it exercises. */
internal open class FakeCore : MessagingCore {
    override val version: String = "test"

    override fun openStore() = Unit
    override fun start(config: CoreStartConfig) = Unit
    override fun stop() = Unit
    override fun setTunnelState(running: Boolean) = Unit
    override fun setSendReadReceipts(enabled: Boolean) = Unit

    override fun setOnline(isOnline: Boolean) = Unit

    override fun chatView(peerIp: String, limit: Int): ChatView = ChatView()

    override fun chatViewFrom(peerIp: String, from: MessageCursor, limit: Int): ChatView = ChatView()

    override fun messagesBefore(peerIp: String, before: MessageCursor, limit: Int): List<ChatMessage> = emptyList()

    override fun chatRows(peerIp: String, ids: Collection<String>): ChatView = ChatView()
    override fun chatSummaries(): List<ChatSummary> = emptyList()
    override fun contacts(): List<Contact> = emptyList()
    override fun contact(ip: String): Contact? = null
    override fun message(id: String): ChatMessage? = null
    override fun messageActions(messageId: String): List<MessageAction> = emptyList()
    override fun searchMessages(peerIp: String, query: String, limit: Int): List<ChatMessage> = emptyList()
    override fun exportChatText(peerIp: String, peerLabel: String): String = ""
    override fun allCallLogs(limit: Int): List<CallLog> = emptyList()
    override fun callLogs(peerIp: String, limit: Int): List<CallLog> = emptyList()
    override fun chatMedia(peerIp: String, limit: Int): List<ChatMessage> = emptyList()
    override fun chatLinks(peerIp: String, limit: Int): List<ChatLink> = emptyList()
    override fun chatMediaCount(peerIp: String): Int = 0
    override fun chatLinkMessageCount(peerIp: String): Int = 0
    override fun unreadTotal(): Int = 0
    override fun peerStats(peerIp: String): PeerStats = PeerStats()
    override fun networkStats(): NetworkStats = NetworkStats()
    override fun storageStats(): StorageStats = StorageStats()

    override fun sendText(peerIp: String, body: String, replyToId: String?, isCovered: Boolean) = Unit
    override fun sendAttachment(peerIp: String, path: String, meta: MessageAttachment, replyToId: String?, isCovered: Boolean) = Unit
    override fun reactToMessage(messageId: String, emoji: String) = Unit
    override fun editMessage(messageId: String, newBody: String) = Unit
    override fun deleteForEveryone(messageId: String) = Unit
    override fun deleteForMe(messageId: String) = Unit
    override fun forwardMessage(sourceMessageId: String, targetPeerIp: String) = Unit
    override fun retryAction(actionId: String) = Unit
    override fun retryActionNow(actionId: String) = Unit
    override fun acceptAttachmentOffer(transferId: String, freeBytes: Long) = Unit
    override fun declineAttachmentOffer(transferId: String) = Unit
    override fun cancelIncomingTransfer(transferId: String) = Unit
    override fun cancelAction(actionId: String) = Unit
    override fun retryFailedActions(peerIp: String): Int = 0
    override fun queuedActionCount(peerIp: String): Int = 0
    override fun drainNow(peerIp: String) = Unit
    override fun peerQueue(peerIp: String): PeerQueueState = PeerQueueState(ip = peerIp)
    override fun markChatRead(peerIp: String) = Unit
    override fun clearHistory(peerIp: String) = Unit
    override fun clearAllHistory() = Unit
    override fun logCall(log: CallLog) = Unit

    override fun deleteCallLogs(ids: List<String>) = Unit

    override fun upsertContact(ip: String, name: String) = Unit
    override fun ensureContact(ip: String, name: String) = Unit
    override fun updateContactDetails(ip: String, name: String, nickname: String, notes: String) = Unit
    override fun deleteContact(ip: String) = Unit
    override fun changeContactIp(oldIp: String, newIp: String) = Unit
    override fun setContactFlags(ip: String, patch: ContactFlagsPatch) = Unit
    override fun setContactNotifications(ip: String, prefs: ContactNotificationPrefs?) = Unit

    override fun setContactPrivacy(ip: String, prefs: ContactPrivacyPrefs) = Unit

    override fun clearOrphanAttachments(): Long = 0
    override fun createBackup(prefsPath: String, destinationPath: String, appVersion: String) = BackupSummary()
    override fun importBackup(archivePath: String, prefsPath: String) = BackupSummary()

    override suspend fun pingPeer(peerIp: String, timeoutMs: Int): Long = -1
    override suspend fun sendSignal(peerIp: String, signal: OutboundSignal, timeoutMs: Int) = Unit
}

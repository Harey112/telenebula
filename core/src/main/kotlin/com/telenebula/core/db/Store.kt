package com.telenebula.core.db

import com.telenebula.core.model.MessageCursor
import com.telenebula.core.model.ContactPrivacyPrefs
import com.telenebula.core.model.CallLog
import com.telenebula.core.model.ChatLink
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.ChatSummary
import com.telenebula.core.model.ChatView
import com.telenebula.core.model.Contact
import com.telenebula.core.model.ContactFlagsPatch
import com.telenebula.core.model.ContactNotificationPrefs
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionPayload
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import com.telenebula.core.model.MessageAttachment
import com.telenebula.core.model.MessageStatus
import com.telenebula.core.model.PeerStats
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLite storage: one facade over the per-aggregate DAOs, all serialised by the one lock exactly
 * as the single connection behind them was before; callers keep it off the main thread.
 */
internal class Store(
    private val db: SqlDb,
    dbPath: String,
    /** how a second database (a backup being merged) is opened; the tests pass their own driver */
    openDatabase: (String) -> SqlDb = AndroidSqlDb::open,
) : AutoCloseable {
    // created here and nowhere else: every DAO must share it, or a read across two would deadlock
    private val lock = ReentrantLock()

    private val contacts = ContactsDao(db, lock)
    private val messages = MessagesDao(db, lock, contacts)
    private val actions = ActionsDao(db, lock)
    private val transfers = TransfersDao(db, lock)
    private val stats = StatsDao(db, lock)
    private val maintenance = MaintenanceDao(db, lock, dbPath, openDatabase, stats)

    fun newId(): String = Ids.newId()

    override fun close() = db.close()

    // --- contacts ---

    fun upsertContact(ip: String, name: String) = contacts.upsertContact(ip, name)
    fun syncContact(ip: String, name: String): Boolean = contacts.syncContact(ip, name)
    fun touchContact(ip: String) = contacts.touchContact(ip)
    fun setClientVersion(ip: String, version: String) = contacts.setClientVersion(ip, version)
    fun updateContactDetails(ip: String, name: String, nickname: String, notes: String) = contacts.updateContactDetails(ip, name, nickname, notes)
    fun getContacts(): List<Contact> = contacts.getContacts()
    fun getContact(ip: String): Contact? = contacts.getContact(ip)
    fun isBlocked(ip: String): Boolean = contacts.isBlocked(ip)
    fun setContactFlags(ip: String, patch: ContactFlagsPatch) = contacts.setContactFlags(ip, patch)
    fun setContactNotifications(ip: String, prefs: ContactNotificationPrefs?) = contacts.setContactNotifications(ip, prefs)
    fun setContactPrivacy(ip: String, prefs: ContactPrivacyPrefs) = contacts.setContactPrivacy(ip, prefs)
    fun clearMessages(peerIp: String) = maintenance.clearMessages(peerIp)
    fun deleteContact(ip: String) = maintenance.deleteContact(ip)
    fun changeContactIp(oldIp: String, newIp: String) = maintenance.changeContactIp(oldIp, newIp)

    // --- messages ---

    fun insertMessage(message: ChatMessage): Boolean = messages.insertMessage(message)
    fun setMessageStatus(id: String, status: MessageStatus) = messages.setMessageStatus(id, status)
    fun getMessage(id: String): ChatMessage? = messages.getMessage(id)
    fun getMessages(peerIp: String, limit: Long): List<ChatMessage> = messages.getMessages(peerIp, limit)
    fun markChatRead(peerIp: String) = messages.markChatRead(peerIp)
    fun sweepExpired(nowMs: Long, inFlight: Set<String> = emptySet()): List<Pair<String, String?>> = maintenance.sweepExpired(nowMs, inFlight)
    fun searchMessages(peerIp: String, query: String, limit: Long): List<ChatMessage> = messages.searchMessages(peerIp, query, limit)
    fun allMessages(peerIp: String, limit: Long): List<ChatMessage> = messages.allMessages(peerIp, limit)
    fun setReaction(messageId: String, byIp: String, emoji: String): Boolean = messages.setReaction(messageId, byIp, emoji)
    fun removeReaction(messageId: String, byIp: String): Boolean = messages.removeReaction(messageId, byIp)
    fun applyEdit(messageId: String, newBody: String, fromIp: String?): Boolean = messages.applyEdit(messageId, newBody, fromIp)
    fun unreportedSeenIds(peerIp: String, limit: Long): List<String> = messages.unreportedSeenIds(peerIp, limit)
    fun pendingSeenActionsFor(messageIds: List<String>): List<MessageAction> = actions.pendingSeenActionsFor(messageIds)
    fun markSeenUnreported(id: String) = messages.markSeenUnreported(id)
    fun markSeenReported(ids: List<String>) = messages.markSeenReported(ids)
    fun setSeenByPeer(ids: List<String>, peerIp: String): Boolean = messages.setSeenByPeer(ids, peerIp)
    fun applyDeleteForEveryone(messageId: String, fromIp: String?): Boolean = messages.applyDeleteForEveryone(messageId, fromIp)
    fun markDeleted(messageId: String) = messages.markDeleted(messageId)
    fun unmarkDeleted(messageId: String) = messages.unmarkDeleted(messageId)
    fun wipeDeletedContent(messageId: String) = messages.wipeDeletedContent(messageId)
    fun restoreBody(messageId: String, body: String, isEdited: Boolean) = messages.restoreBody(messageId, body, isEdited)
    fun deleteMessageForMe(messageId: String) = messages.deleteMessageForMe(messageId)
    fun getUnreadTotal(): Int = messages.getUnreadTotal()
    fun getLegacyInlineAttachmentIds(limit: Long): List<String> = messages.getLegacyInlineAttachmentIds(limit)
    fun clearMessageAttachment(id: String) = messages.clearMessageAttachment(id)
    fun setMessageAttachment(id: String, attachment: MessageAttachment) = messages.setMessageAttachment(id, attachment)

    // --- attachment transfers ---

    fun upsertTransfer(transferId: String, peerIp: String, isIncoming: Boolean, state: Wire.TransferState, size: Long) =
        transfers.upsertTransfer(transferId, peerIp, isIncoming, state, size)
    fun setTransferState(transferId: String, state: Wire.TransferState, reason: String? = null) = transfers.setTransferState(transferId, state, reason)
    fun abandonedTransferIds(untouchedSince: Long): List<String> = transfers.abandonedTransferIds(untouchedSince)
    fun resumableTransferIds(): List<String> = transfers.resumableTransferIds()
    fun transferState(transferId: String): Wire.TransferState? = transfers.transferState(transferId)
    fun transferIsAccepted(transferId: String): Boolean = transfers.transferIsAccepted(transferId)
    fun unfinishedTransferIds(): List<String> = transfers.unfinishedTransferIds()
    fun transferPeer(transferId: String): String? = transfers.transferPeer(transferId)
    fun transferReceived(transferId: String): Long = transfers.transferReceived(transferId)
    fun setTransferReceived(transferId: String, chunks: Long) = transfers.setTransferReceived(transferId, chunks)
    fun pendingTransfersForPeer(peerIp: String, isIncoming: Boolean): List<String> = transfers.pendingTransfersForPeer(peerIp, isIncoming)

    // --- actions (the outbox) ---

    fun createAction(action: MessageAction) = actions.createAction(action)
    fun getAction(id: String): MessageAction? = actions.getAction(id)
    fun getActionsForMessage(messageId: String): List<MessageAction> = actions.getActionsForMessage(messageId)
    fun peersWithOpenActions(limit: Long): List<PeerQueueRow> = actions.peersWithOpenActions(limit)
    fun pendingActionsForPeer(peerIp: String, limit: Long): List<MessageAction> = actions.pendingActionsForPeer(peerIp, limit)
    fun openActionCount(peerIp: String): Int = actions.openActionCount(peerIp)
    fun pendingActionCount(peerIp: String): Int = actions.pendingActionCount(peerIp)
    fun setActionPayload(id: String, payload: MessageActionPayload) = actions.setActionPayload(id, payload)
    fun openActionOf(messageId: String, type: MessageActionType): MessageAction? = actions.openActionOf(messageId, type)
    fun setActionStatus(id: String, status: MessageActionStatus) = actions.setActionStatus(id, status)
    fun setActionOutcome(id: String, status: MessageActionStatus, reason: String) = actions.setActionOutcome(id, status, reason)
    fun resetActionForRetry(id: String) = actions.resetActionForRetry(id)
    fun backfillSendActions() = actions.backfillSendActions()
    fun failedActionIds(peerIp: String?): List<String> = actions.failedActionIds(peerIp)

    // --- traffic and stats ---

    fun addTraffic(ip: String, sent: Long, received: Long) = stats.addTraffic(ip, sent, received)
    fun totalTraffic(): Pair<Long, Long> = stats.totalTraffic()
    fun actionCounts(peerIp: String?): Pair<Int, Int> = actions.actionCounts(peerIp)

    /** The one composed read: message and traffic figures from one table, the queue figures from another. */
    fun peerStats(peerIp: String): PeerStats = lock.withLock {
        val (pending, failed) = actions.actionCounts(peerIp)
        stats.messageStats(peerIp).copy(pendingActions = pending, failedActions = failed)
    }

    fun referencedAttachmentPaths(): Set<String> = maintenance.referencedAttachmentPaths()
    fun tableCounts(): Pair<Int, Int> = maintenance.tableCounts()
    fun clearAllMessages() = maintenance.clearAllMessages()
    fun snapshotTo(destination: String) = maintenance.snapshotTo(destination)
    fun mergeFrom(sourcePath: String, oldDir: String, newDir: String): Pair<Int, Int> = maintenance.mergeFrom(sourcePath, oldDir, newDir)

    // --- call logs ---

    fun insertCallLog(log: CallLog) = stats.insertCallLog(log)
    fun getAllCallLogs(limit: Long): List<CallLog> = stats.getAllCallLogs(limit)
    fun deleteCallLogs(ids: List<String>) = stats.deleteCallLogs(ids)
    fun getCallLogs(peerIp: String, limit: Long): List<CallLog> = stats.getCallLogs(peerIp, limit)

    // --- coarse view models ---

    fun getChatMedia(peerIp: String, limit: Long): List<ChatMessage> = messages.getChatMedia(peerIp, limit)
    fun countChatMedia(peerIp: String): Int = messages.countChatMedia(peerIp)
    fun getChatLinks(peerIp: String, limit: Long): List<ChatLink> = messages.getChatLinks(peerIp, limit)
    fun countChatLinkMessages(peerIp: String): Int = messages.countChatLinkMessages(peerIp)
    fun getChatSummaries(): List<ChatSummary> = messages.getChatSummaries()
    fun getChatView(peerIp: String, limit: Long): ChatView = messages.getChatView(peerIp, limit)
    fun getChatView(peerIp: String, from: MessageCursor, limit: Long): ChatView = messages.getChatView(peerIp, from, limit)
    fun getChatRows(peerIp: String, ids: Collection<String>): ChatView = messages.getChatRows(peerIp, ids)
    fun getMessagesBefore(peerIp: String, before: MessageCursor, limit: Long): List<ChatMessage> = messages.getMessagesBefore(peerIp, before, limit)

    companion object {
        fun open(dbPath: String): Store = Store(AndroidSqlDb.open(dbPath), dbPath).also { Schema.apply(it.db) }

        /** http(s) URLs in free text, in order, with trailing punctuation trimmed. */
        fun extractUrls(text: String): List<String> = MessagesDao.extractUrls(text)
    }
}

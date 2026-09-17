package com.telenebula.core.engine

import com.telenebula.core.CoreException
import com.telenebula.core.CorePaths
import com.telenebula.core.Ip
import com.telenebula.core.db.Wire
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionPayload
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import com.telenebula.core.model.MessageAttachment
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * The command surface of the action queue: what creates, supersedes, cancels and re-queues a
 * remote effect. *When* one runs is [DeliveryScheduler]'s business and *how* is [ActionSender]'s —
 * every command here ends by kicking the peer, never by delivering anything itself.
 *
 * Actions on one message run in strict order, and cancelling the send cascades to the rest.
 */
internal class Outbox(private val engine: Engine) {
    private val store get() = engine.store

    // --- queueing ---

    private fun newAction(
        type: MessageActionType,
        messageId: String,
        peerIp: String,
        payload: MessageActionPayload = MessageActionPayload(),
    ): MessageAction {
        val stamp = System.currentTimeMillis()
        return MessageAction(
            // the send action shares the message id so envelope and ack line up one to one
            id = if (type == MessageActionType.SEND) messageId else engine.newId(),
            messageId = messageId,
            peerIp = Ip.normalize(peerIp),
            type = type,
            payload = payload,
            status = MessageActionStatus.PENDING,
            attempts = 0,
            createdAt = stamp,
            updatedAt = stamp,
        )
    }

    /**
     * Queues one action and puts its peer at the front of the probe ladder. Nothing is delivered
     * here: the scheduler decides when, which is what makes twenty actions to one absent peer cost
     * one probe rather than twenty attempts.
     */
    private fun enqueue(action: MessageAction) {
        store.createAction(action)
        engine.events.chatChanged(action.peerIp)
        engine.delivery.kick(action.peerIp)
    }

    /** Ends an action and tells the app in one step; a status the screen never hears about is a stale bubble. */
    fun finish(actionId: String, peerIp: String, status: MessageActionStatus, reason: String? = null) {
        if (reason == null) store.setActionStatus(actionId, status) else store.setActionOutcome(actionId, status, reason)
        announceAction(actionId, peerIp)
    }

    /** An action's row is its message's row to the reader. */
    private fun announceAction(actionId: String, peerIp: String) {
        val messageId = store.getAction(actionId)?.messageId
        if (messageId == null) engine.events.chatChanged(peerIp) else engine.events.messagesChanged(peerIp, listOf(messageId))
    }

    /**
     * True when this peer's queue is full. Nothing else bounds a queue that never gives up, and
     * refusing the next message out loud beats swallowing it.
     */
    fun isQueueFull(peerIp: String): Boolean =
        store.pendingActionCount(Ip.normalize(peerIp)) >= Limits.MAX_PENDING_PER_PEER

    /** Re-queues every failed action (optionally for one peer); returns how many. */
    fun retryFailed(peerIp: String?): Int {
        var total = 0
        // a page at a time, and retryAction always moves a row out of 'failed', so this drains
        while (true) {
            val ids = store.failedActionIds(peerIp)
            if (ids.isEmpty()) return total
            for (id in ids) retryAction(id)
            total += ids.size
        }
    }

    /** One row per message; with receipts off they are flagged anyway, so none is sent later. */
    fun reportSeen(peerIp: String) {
        val ip = Ip.normalize(peerIp)
        if (!engine.sendsReadReceiptsTo(ip)) {
            store.markSeenReported(store.unreportedSeenIds(ip, Limits.SEEN_BATCH))
            return
        }
        // receipts must never be what refuses a send, so they take only the room left over
        val room = (Limits.MAX_PENDING_PER_PEER - store.pendingActionCount(ip))
            .coerceAtMost(Limits.SEEN_BATCH.toInt())
        if (room <= 0) return
        val ids = store.unreportedSeenIds(ip, room.toLong())
        if (ids.isEmpty()) return

        val queued = store.pendingSeenActionsFor(ids).mapTo(HashSet()) { it.messageId }
        var created = 0
        for (id in ids) {
            if (id in queued) continue
            store.createAction(newAction(MessageActionType.SEEN, id, ip))
            created++
        }
        if (created > 0) engine.events.messagesChanged(ip, ids)
        engine.delivery.kick(ip)
    }

    /** Re-applies an action's local (optimistic) effect — on create and on retry. */
    private fun applyLocalEffect(action: MessageAction) = action.type.kind.applyLocal(store, action, engine.profile.overlayIp)

    /** Undoes an action's local effect, when a pending action is cancelled. */
    private fun revertLocalEffect(action: MessageAction) = action.type.kind.revertLocal(store, action, engine.profile.overlayIp)

    /**
     * Cancels a pending action and rolls back its local effect. Cancelling the send cascades to
     * every other unfinished action of that message.
     */
    fun cancelAction(actionId: String) {
        val action = store.getAction(actionId) ?: return
        // a send parked on an unanswered offer is cancellable too: it is waiting on another
        // person, so withdrawing it is the only way out short of waiting forever
        if (action.status != MessageActionStatus.PENDING && action.status != MessageActionStatus.WAITING) return
        val wasOffered = action.status == MessageActionStatus.WAITING

        if (action.type == MessageActionType.SEND) {
            val others = store.getActionsForMessage(action.messageId)
                .filter { it.id != action.id && it.status == MessageActionStatus.PENDING }
            // revert newest first so earlier snapshots (an edit's previous body) win
            for (other in others.asReversed()) {
                store.setActionStatus(other.id, MessageActionStatus.CANCELLED)
                revertLocalEffect(other)
            }
        }

        store.setActionStatus(action.id, MessageActionStatus.CANCELLED)
        revertLocalEffect(action)
        // parked or half-streamed, the other side is left holding a question or a frozen percentage
        val transfer = store.transferState(action.id)
        if (wasOffered || transfer?.canMoveTo(Wire.TransferState.CANCELLED) == true) {
            store.setTransferState(action.id, Wire.TransferState.CANCELLED)
            engine.sending.withdrawOffer(action.id, action.peerIp)
        }
        engine.events.messagesChanged(action.peerIp, listOf(action.messageId))

        // whatever was queued behind it is the head now, so the peer is worth another look
        if (action.type != MessageActionType.SEND) engine.delivery.kick(action.peerIp)
    }

    /** Re-queues a failed or cancelled action. A user asking again is not an automatic retry. */
    fun retryAction(actionId: String) {
        val action = store.getAction(actionId) ?: return
        if (action.status == MessageActionStatus.PENDING || action.status == MessageActionStatus.SUCCESS) return
        applyLocalEffect(action)
        requeue(actionId, action.peerIp)
    }

    private fun requeue(actionId: String, peerIp: String) {
        store.resetActionForRetry(actionId)
        announceAction(actionId, peerIp)
        engine.delivery.kick(peerIp)
    }

    /**
     * The user asked for this to happen now rather than at the peer's next scheduled probe. That is
     * a statement about the peer, not the action: it resets the peer to the front of the ladder and
     * drains it, so everything else waiting on the same peer goes too.
     */
    fun retryActionNow(actionId: String) {
        val action = store.getAction(actionId) ?: return
        if (action.status == MessageActionStatus.SUCCESS) return
        if (action.status != MessageActionStatus.PENDING) return retryAction(actionId)
        engine.delivery.kick(action.peerIp)
    }

    /**
     * An offer waits with no timeout, so an answer may need to reach a sender that has been away
     * for days. On reconnect every unanswered offer to this peer is put back in the queue and
     * re-sent; the receiver answers from the decision it already stored, so the two sides converge
     * no matter which of them came back first.
     */
    fun renewPendingOffers(peerIp: String) {
        for (id in store.pendingTransfersForPeer(peerIp, isIncoming = false)) {
            val action = store.getAction(id) ?: continue
            if (action.status != MessageActionStatus.WAITING) continue
            store.resetActionForRetry(id)
            engine.delivery.kick(peerIp)
        }
    }

    /** The peer accepted an offer: the parked send goes back in the queue and runs now. */
    fun resumeAcceptedSend(actionId: String) {
        val action = store.getAction(actionId) ?: return
        if (action.status != MessageActionStatus.WAITING) return
        requeue(actionId, action.peerIp)
    }

    // --- transfer control ---

    /**
     * The four answers to an attachment offer. They were written straight to the socket before, so
     * a decision made while the other side was away simply vanished; queued, it is delivered when
     * they come back like anything else.
     */
    fun enqueueOfferAnswer(transferId: String, peerIp: String, accept: Boolean, reason: String?, resumeFrom: Long) {
        val type = if (accept) MessageActionType.ATT_ACCEPT else MessageActionType.ATT_DECLINE
        enqueueControl(type, transferId, peerIp, MessageActionPayload(transferId = transferId, reason = reason, resumeFrom = resumeFrom))
    }

    fun enqueueOfferWithdrawal(transferId: String, peerIp: String) {
        enqueueControl(
            MessageActionType.ATT_CANCEL,
            transferId,
            peerIp,
            MessageActionPayload(transferId = transferId),
        )
    }

    fun enqueueTransferError(transferId: String, peerIp: String, reason: String) {
        enqueueControl(
            MessageActionType.ATT_ERROR,
            transferId,
            peerIp,
            MessageActionPayload(transferId = transferId, reason = reason),
        )
    }

    /** One open control action per transfer per kind: repeating the answer does not repeat the row. */
    private fun enqueueControl(
        type: MessageActionType,
        transferId: String,
        peerIp: String,
        payload: MessageActionPayload,
    ) {
        val ip = Ip.normalize(peerIp)
        val existing = store.openActionOf(transferId, type)
        if (existing != null) {
            store.setActionPayload(existing.id, payload)
            engine.delivery.kick(ip)
            return
        }
        enqueue(newAction(type, transferId, ip, payload))
    }

    // --- message commands ---

    fun sendText(peerIp: String, body: String, replyToId: String?) {
        requireRoom(peerIp)
        createOutgoing(peerIp, body, null, MessageKind.TEXT, replyToId, null)
    }

    /**
     * A queue that never gives up needs some ceiling, or a peer gone for good would swallow an
     * unbounded amount. Saying so is better than accepting a message that will never be read.
     */
    private fun requireRoom(peerIp: String) {
        if (isQueueFull(peerIp)) {
            throw CoreException.internal(
                "Too much is already waiting for this contact (${Limits.MAX_PENDING_PER_PEER} actions). " +
                    "Wait until they are back online.",
            )
        }
    }

    /**
     * The file must already live in app storage (the app copies a picked `content://` there); only
     * its path is handed over.
     */
    fun sendAttachment(peerIp: String, path: String, meta: MessageAttachment, replyToId: String?) {
        requireRoom(peerIp)
        val attachment = MessageAttachment(
            name = meta.name,
            mime = meta.mime,
            size = meta.size,
            uri = if (path.startsWith("file://")) path else CorePaths.pathToUri(path),
            width = meta.width?.takeIf { it > 0 },
            height = meta.height?.takeIf { it > 0 },
        )
        createOutgoing(peerIp, "", attachment, Wire.kindForMime(meta.mime), replyToId, null)
    }

    private fun createOutgoing(
        peerIp: String,
        body: String,
        attachment: MessageAttachment?,
        kind: MessageKind,
        replyToId: String?,
        presetId: String?,
    ) {
        val ip = Ip.normalize(peerIp)
        // the chat's disappearing timer: our copy counts from now, the peer's from first read
        val expireSecs = store.getContact(ip)?.disappearSeconds?.takeIf { it > 0 }?.toLong()
        val ts = System.currentTimeMillis()
        val message = ChatMessage(
            id = presetId ?: engine.newId(),
            peerIp = ip,
            direction = MessageDirection.OUT,
            body = body,
            ts = ts,
            status = MessageStatus.PENDING,
            kind = kind,
            attachment = attachment,
            replyToId = replyToId,
            expireSecs = expireSecs,
            expiresAt = expireSecs?.let { ts + it * 1000 },
            isRead = true,
        )
        store.insertMessage(message)
        enqueue(newAction(MessageActionType.SEND, message.id, ip))
    }

    /**
     * Reaction with cancel and replace semantics: the same emoji tapped while it is still sending
     * cancels it, an established reaction re-tapped delivers a removal, anything else replaces.
     */
    fun reactToMessage(messageId: String, emoji: String) {
        val message = store.getMessage(messageId) ?: return
        if (message.isDeleted) return

        val pendingReacts = store.getActionsForMessage(messageId)
            .filter { it.type == MessageActionType.REACT && it.status == MessageActionStatus.PENDING }
        var cancelledSame = false
        for (pending in pendingReacts) {
            if (pending.payload.emoji == emoji) cancelledSame = true
            cancelAction(pending.id)
        }
        // the same emoji tapped while it (or its removal) was still sending → cancel only; the
        // cancel already restored the previous state
        if (cancelledSame) {
            engine.events.summariesChanged()
            return
        }

        val mine = store.getMessage(messageId)?.reactions?.get(engine.profile.overlayIp)
        if (mine == emoji) {
            // toggling off an established reaction → deliver the removal
            store.removeReaction(messageId, engine.profile.overlayIp)
            enqueue(
                newAction(
                    MessageActionType.REACT,
                    messageId,
                    message.peerIp,
                    MessageActionPayload(emoji = emoji, remove = true),
                ),
            )
        } else {
            store.setReaction(messageId, engine.profile.overlayIp, emoji)
            enqueue(newAction(MessageActionType.REACT, messageId, message.peerIp, MessageActionPayload(emoji = emoji)))
        }
    }

    /**
     * Own text messages only; applied locally right away, then delivered. An edit of an edit that
     * has not gone yet rewrites the queued one rather than queueing another — the peer only ever
     * needs the latest body, and N edits to an absent peer should not cost N round trips. The
     * oldest `prevBody` is kept, so cancelling still restores what was there before the first edit.
     */
    fun editMessage(messageId: String, newBody: String) {
        val message = store.getMessage(messageId) ?: return
        if (message.direction != MessageDirection.OUT || message.isDeleted || message.kind != MessageKind.TEXT) return
        val body = newBody.trim()
        if (body.isEmpty() || body == message.body) return
        store.applyEdit(messageId, body, null)

        val queued = store.openActionOf(messageId, MessageActionType.EDIT)
        if (queued != null && queued.id !in engine.inFlight) {
            store.setActionPayload(queued.id, queued.payload.copy(body = body))
            engine.events.messagesChanged(message.peerIp, listOf(messageId))
            engine.delivery.kick(message.peerIp)
            return
        }
        enqueue(
            newAction(
                MessageActionType.EDIT,
                messageId,
                message.peerIp,
                MessageActionPayload(body = body, prevBody = message.body, prevEdited = message.isEdited),
            ),
        )
    }

    /**
     * Own messages only. Marked deleted locally right away; the content is wiped only once the
     * peer confirms, so a cancelled delete can restore it.
     */
    fun deleteForEveryone(messageId: String) {
        val message = store.getMessage(messageId) ?: return
        if (message.direction != MessageDirection.OUT || message.isDeleted) return
        store.markDeleted(messageId)
        enqueue(newAction(MessageActionType.DELETE, messageId, message.peerIp))
    }

    /** Copies the message into another chat as a brand-new outgoing message. */
    fun forwardMessage(sourceMessageId: String, targetPeerIp: String) {
        val source = store.getMessage(sourceMessageId) ?: return
        if (source.isDeleted) return
        val attachment = source.attachment
        val uri = attachment?.uri
        if (attachment != null && uri != null) {
            val id = engine.newId()
            val destination = engine.attachments.attachmentFile(id, attachment.name)
            engine.attachmentsDir.mkdirs()
            File(CorePaths.uriToPath(uri)).copyTo(destination, overwrite = true)
            createOutgoing(
                targetPeerIp,
                source.body,
                attachment.copy(uri = CorePaths.pathToUri(destination.path), dataB64 = null),
                source.kind,
                null,
                id,
            )
            return
        }
        createOutgoing(targetPeerIp, source.body, source.attachment, source.kind, null, null)
    }

    /**
     * Converts legacy inline base64 attachments to files, in bounded batches. Convergence is
     * guaranteed: every processed row either gains a file uri or loses its inline payload.
     */
    suspend fun migrateLegacyInlineAttachments() {
        while (currentCoroutineContext().isActive) {
            val ids = runCatching { store.getLegacyInlineAttachmentIds(Limits.LEGACY_ATTACHMENT_BATCH) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            for (id in ids) {
                val attachment = store.getMessage(id)?.attachment
                val data = attachment?.dataB64
                if (attachment == null || data == null) {
                    // the LIKE matched but the payload is unusable
                    store.clearMessageAttachment(id)
                    continue
                }
                val uri = runCatching { engine.attachments.writeInlineAttachment(id, attachment.name, data) }.getOrNull()
                if (uri == null) {
                    store.clearMessageAttachment(id)
                } else {
                    store.setMessageAttachment(id, attachment.copy(uri = uri, dataB64 = null))
                }
            }
        }
    }

    companion object {
        /**
         * Strict per-message sequencing: the running action is the first (chronologically) that is
         * not finished — and only if it is pending. A failed action, or one waiting on an offer
         * nobody has answered, stalls the queue until it is retried or cancelled.
         */
        fun runningActionId(actions: List<MessageAction>): String? {
            for (action in actions) {
                when (action.status) {
                    MessageActionStatus.SUCCESS, MessageActionStatus.CANCELLED -> continue
                    MessageActionStatus.PENDING -> return action.id
                    else -> return null
                }
            }
            return null
        }
    }
}

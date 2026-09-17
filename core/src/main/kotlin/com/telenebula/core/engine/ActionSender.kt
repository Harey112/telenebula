package com.telenebula.core.engine

import com.telenebula.core.CoreException
import com.telenebula.core.CoreLog
import com.telenebula.core.db.Wire
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.Envelope
import com.telenebula.core.model.EnvelopeType
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import com.telenebula.core.model.MessageStatus

/**
 * How one delivery attempt ended. The distinction that matters is the last one: only [SILENT] says
 * anything about the *peer* rather than the action, and it is what ends a whole drain — otherwise
 * a peer that went away mid-queue would cost an ack timeout for every action still waiting.
 */
internal enum class AttemptResult {
    /** the peer acked, or a control frame was written to a link it had just answered on */
    DELIVERED,

    /** the peer answered and the answer was no; retrying cannot change it */
    REFUSED,

    /** nothing to do: not at the head of its message, parked on an offer, or finished already */
    SKIPPED,

    /** no link, or no ack before the timeout: the peer is gone, and so is this drain */
    SILENT,
}

/**
 * One action, once. Everything about *when* to run one lives in [DeliveryScheduler]; this only
 * knows how to turn a queued row into a frame and report what came back.
 */
internal class ActionSender(private val engine: Engine) {
    private val store get() = engine.store

    /**
     * Safe to call concurrently: it reports [AttemptResult.SKIPPED] when the action is not at the
     * head of its message's queue, or when an attempt is already running for it.
     */
    suspend fun attempt(actionId: String): AttemptResult {
        if (!engine.inFlight.add(actionId)) return AttemptResult.SKIPPED
        return try {
            run(actionId)
        } catch (e: Exception) {
            CoreLog.warn(TAG, "attempt $actionId errored: ${e.message}")
            engine.events.fault("Sending failed", e.describe())
            AttemptResult.SKIPPED
        } finally {
            engine.inFlight.remove(actionId)
        }
    }

    private suspend fun run(actionId: String): AttemptResult {
        val action = store.getAction(actionId) ?: return AttemptResult.SKIPPED
        if (action.status != MessageActionStatus.PENDING) return AttemptResult.SKIPPED
        // a type this build does not know was written by a newer one; it is renderable, not runnable
        if (action.type == MessageActionType.UNKNOWN) {
            engine.outbox.finish(action.id, action.peerIp, MessageActionStatus.CANCELLED)
            return AttemptResult.SKIPPED
        }
        if (!isRunnable(action)) return AttemptResult.SKIPPED

        if (action.type == MessageActionType.SEEN) return deliverSeen(action)
        if (action.type.isTransferControl) return deliverControl(action)

        val message = store.getMessage(action.messageId) ?: run {
            // the message was deleted locally — nothing left to do
            engine.outbox.finish(action.id, action.peerIp, MessageActionStatus.FAILED)
            return AttemptResult.SKIPPED
        }
        // an unknown type that decoded to SEND would otherwise put someone else's message on the
        // wire; a send only ever belongs to an outgoing message
        if (action.type == MessageActionType.SEND && message.direction != com.telenebula.core.model.MessageDirection.OUT) {
            store.setActionStatus(action.id, MessageActionStatus.CANCELLED)
            return AttemptResult.SKIPPED
        }

        // file-backed attachments go through the chunked path; only a small legacy inline blob
        // may still travel in a single frame
        val attachmentUri = message.attachment
            ?.takeIf { action.type == MessageActionType.SEND && it.dataB64 == null }
            ?.uri
        val envelope = if (attachmentUri == null) envelopeFor(action, message) else null
        if (attachmentUri == null && envelope == null) {
            engine.outbox.finish(action.id, action.peerIp, MessageActionStatus.FAILED)
            return AttemptResult.SKIPPED
        }

        val outcome = deliver(action, message, attachmentUri, envelope)

        val fresh = store.getAction(actionId) ?: return AttemptResult.SKIPPED
        if (fresh.status != MessageActionStatus.PENDING) {
            // parked on an offer while we were delivering: a wait is not a failure
            engine.events.messagesChanged(fresh.peerIp, listOf(fresh.messageId))
            return AttemptResult.SKIPPED
        }

        val result = when (outcome) {
            is AckOutcome.Ack -> {
                store.setActionStatus(actionId, MessageActionStatus.SUCCESS)
                if (fresh.type == MessageActionType.SEND) {
                    store.setMessageStatus(fresh.messageId, MessageStatus.DELIVERED)
                }
                // the peer confirmed the delete — now the local content can go too
                if (fresh.type == MessageActionType.DELETE) store.wipeDeletedContent(fresh.messageId)
                AttemptResult.DELIVERED
            }
            // the peer answered and the answer was no: the reason is kept for the bubble to show
            is AckOutcome.Nack -> {
                store.setActionOutcome(actionId, MessageActionStatus.FAILED, outcome.reason)
                AttemptResult.REFUSED
            }
            // silence is never a failure any more: the action stays queued and the peer is probed
            is AckOutcome.Timeout -> AttemptResult.SILENT
        }
        engine.events.messagesChanged(fresh.peerIp, listOf(fresh.messageId))
        if (attachmentUri != null) engine.events.transferProgress(actionId, TransferManager.CLEAR_PROGRESS)
        return result
    }

    /** One frame per batch: the ids are read here, and the flag is set only once the peer acked. */
    private suspend fun deliverSeen(action: MessageAction): AttemptResult {
        // flagging them is what stops the next batch carrying them anyway
        if (!engine.sendsReadReceiptsTo(action.peerIp)) {
            val withdrawn = store.unreportedSeenIds(action.peerIp, Limits.SEEN_BATCH)
            store.markSeenReported(withdrawn)
            for (queued in store.pendingSeenActionsFor(withdrawn)) {
                store.setActionStatus(queued.id, MessageActionStatus.CANCELLED)
            }
            engine.outbox.finish(action.id, action.peerIp, MessageActionStatus.CANCELLED)
            return AttemptResult.SKIPPED
        }
        val ids = store.unreportedSeenIds(action.peerIp, Limits.SEEN_BATCH)
        if (ids.isEmpty()) {
            engine.outbox.finish(action.id, action.peerIp, MessageActionStatus.SUCCESS)
            return AttemptResult.DELIVERED
        }
        val envelope = engine.transport.envelope(EnvelopeType.SEEN).copy(id = action.id, targetIds = ids)
        return when (send(action.peerIp, envelope, awaitAck = true)) {
            is AckOutcome.Ack -> {
                store.markSeenReported(ids)
                for (carried in store.pendingSeenActionsFor(ids)) {
                    store.setActionStatus(carried.id, MessageActionStatus.SUCCESS)
                }
                // the row that triggered the frame may not be one it carried (a deleted message)
                engine.outbox.finish(action.id, action.peerIp, MessageActionStatus.SUCCESS)
                engine.outbox.reportSeen(action.peerIp)
                AttemptResult.DELIVERED
            }
            is AckOutcome.Nack -> {
                engine.outbox.finish(action.id, action.peerIp, MessageActionStatus.CANCELLED)
                AttemptResult.REFUSED
            }
            is AckOutcome.Timeout -> AttemptResult.SILENT
        }
    }

    /**
     * An answer to an attachment offer. These are not acked by any peer — no build ever has — so a
     * successful write is the completion. That is sound only because of how the queue runs them:
     * the drain writes to a peer that answered a probe (or spoke to us) seconds earlier, which is
     * the same reachability argument the call signals already rest on.
     */
    private suspend fun deliverControl(action: MessageAction): AttemptResult {
        val transferId = action.payload.transferId ?: action.messageId
        val type = action.type.kind.envelopeType ?: EnvelopeType.ATT_ERROR
        // a fresh id, never the transfer id: an ack keyed on the transfer id would complete the
        // sender's own pending offer or stream waiter instead of this frame
        val envelope = engine.transport.envelope(type).copy(
            targetId = transferId,
            reason = action.payload.reason,
            seq = action.payload.resumeFrom,
        )
        return when (send(action.peerIp, envelope, awaitAck = false)) {
            is AckOutcome.Ack -> {
                engine.outbox.finish(action.id, action.peerIp, MessageActionStatus.SUCCESS)
                AttemptResult.DELIVERED
            }
            else -> AttemptResult.SILENT
        }
    }

    /** Writes one envelope, optionally waiting for its ack. A missing link is silence, not failure. */
    private suspend fun send(peerIp: String, envelope: Envelope, awaitAck: Boolean): AckOutcome {
        val link = try {
            engine.transport.link(peerIp)
        } catch (_: CoreException) {
            return AckOutcome.Timeout
        }
        if (!awaitAck) return if (engine.transport.send(link, envelope)) AckOutcome.Ack else AckOutcome.Timeout
        return engine.acks.register(envelope.id).use { waiter ->
            if (!engine.transport.send(link, envelope)) return@use AckOutcome.Timeout
            waiter.await()
        }
    }

    private suspend fun deliver(
        action: MessageAction,
        message: ChatMessage,
        attachmentUri: String?,
        envelope: Envelope?,
    ): AckOutcome {
        val link = try {
            engine.transport.link(action.peerIp)
        } catch (_: CoreException) {
            return AckOutcome.Timeout
        }

        if (attachmentUri != null) {
            val size = message.attachment?.size ?: 0
            val mustAsk = size > Limits.ATT_OFFER_THRESHOLD_BYTES && !store.transferIsAccepted(action.id)
            if (!mustAsk) {
                return runCatching { engine.sending.sendChunked(link, action, message, attachmentUri) }
                    .getOrDefault(AckOutcome.Timeout)
            }
            return when (val answer = engine.sending.sendOffer(link, action, message, size)) {
                is AckOutcome.Ack -> {
                    // parked: the drain skips a waiting action entirely, so the wait costs no
                    // probes and needs no timer to sit through
                    store.upsertTransfer(action.id, action.peerIp, isIncoming = false, state = Wire.TransferState.OFFERED, size = size)
                    store.setActionStatus(action.id, MessageActionStatus.WAITING)
                    engine.events.messagesChanged(action.peerIp, listOf(action.messageId))
                    AckOutcome.Timeout // the caller sees the action is no longer pending and stops
                }
                // No answer to the offer means either the peer is away or it is too old to know
                // the frame, and we cannot tell which. Below the auto-accept size the old direct
                // path still works on both, so take it rather than guess.
                else -> if (size <= Limits.ATT_AUTO_ACCEPT_BYTES) {
                    runCatching { engine.sending.sendChunked(link, action, message, attachmentUri) }
                        .getOrDefault(AckOutcome.Timeout)
                } else {
                    answer
                }
            }
        }

        if (envelope == null) return AckOutcome.Timeout
        return engine.acks.register(envelope.id).use { waiter ->
            if (!engine.transport.send(link, envelope)) return@use AckOutcome.Timeout
            waiter.await()
        }
    }

    private fun envelopeFor(action: MessageAction, message: ChatMessage): Envelope? {
        val kind = action.type.kind
        val type = kind.envelopeType ?: return null
        return kind.envelope(engine.transport.envelope(type), action, message)
    }

    /** A receipt has no place in its message's order, or a refused react would hold it back forever. */
    private fun isRunnable(action: MessageAction): Boolean =
        if (action.type == MessageActionType.SEEN) true
        else Outbox.runningActionId(store.getActionsForMessage(action.messageId)) == action.id

    companion object {
        private const val TAG = "TnActionSender"
    }
}

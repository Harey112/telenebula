package com.telenebula.core.engine

import com.telenebula.core.CorePaths
import com.telenebula.core.db.Store
import com.telenebula.core.db.Wire
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.Envelope
import com.telenebula.core.model.EnvelopeType
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageDirection
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64

/**
 * The sending half of attachment transfers: the offer, the chunked stream, and the answers the
 * receiver sends back about them. Bytes stream from disk to the socket and never cross into the app.
 */
internal class OutgoingTransfers(private val engine: Engine) {
    private val store get() = engine.store

    /**
     * Asks before streaming. The ack proves only that the offer was delivered; the real answer is
     * an att-accept or att-decline that arrives later, possibly much later.
     */
    suspend fun sendOffer(link: PeerLink, action: MessageAction, message: ChatMessage, size: Long): AckOutcome {
        val attachment = message.attachment ?: return AckOutcome.Timeout
        val offer = engine.transport.envelope(EnvelopeType.ATT_OFFER).copy(
            id = message.id,
            ts = message.ts,
            body = message.body,
            name = attachment.name,
            mime = attachment.mime,
            size = size,
            totalChunks = TransferManager.chunkCount(size),
            width = attachment.width,
            height = attachment.height,
            replyToId = message.replyToId,
            expiresIn = message.expireSecs,
        )
        return engine.acks.register(message.id).use { waiter ->
            if (!engine.transport.send(link, offer)) return@use AckOutcome.Timeout
            waiter.await()
        }
    }

    /**
     * Streams a file-backed attachment as att-begin plus N att-chunk frames. It resolves with an
     * ack only when the peer confirms the completed transfer; progress is quantized before it
     * reaches the event pump.
     */
    suspend fun sendChunked(link: PeerLink, action: MessageAction, message: ChatMessage, uri: String): AckOutcome {
        val attachment = message.attachment ?: return AckOutcome.Timeout
        val file = File(CorePaths.uriToPath(uri))
        val size = if (attachment.size > 0) attachment.size else file.length()
        if (size <= 0) return AckOutcome.Timeout
        val totalChunks = TransferManager.chunkCount(size)

        // the receiver told us how much it already holds; seek past it rather than resending
        val resumeFrom = store.transferReceived(message.id).coerceIn(0, totalChunks - 1)

        return RandomAccessFile(file, "r").use { source ->
            if (resumeFrom > 0) source.seek(resumeFrom * Limits.ATT_CHUNK_BYTES)

            val begin = engine.transport.envelope(EnvelopeType.ATT_BEGIN).copy(
                id = message.id,
                ts = message.ts,
                body = message.body,
                name = attachment.name,
                mime = attachment.mime,
                size = size,
                totalChunks = totalChunks,
                width = attachment.width,
                height = attachment.height,
                replyToId = message.replyToId,
                expiresIn = message.expireSecs,
                seq = resumeFrom,
            )
            engine.acks.register(message.id).use { waiter ->
                if (!engine.transport.send(link, begin)) return@use AckOutcome.Timeout

                val buffer = ByteArray(Limits.ATT_CHUNK_BYTES)
                var lastPct = resumeFrom.toDouble() / totalChunks
                val template = engine.transport.envelope(EnvelopeType.ATT_CHUNK).copy(transferId = message.id)
                for (seq in resumeFrom until totalChunks) {
                    val read = source.read(buffer)
                    if (read <= 0) return@use AckOutcome.Timeout
                    val payload = Base64.getEncoder().encodeToString(if (read == buffer.size) buffer else buffer.copyOf(read))
                    if (!engine.transport.send(link, template.copy(seq = seq, dataB64 = payload))) return@use AckOutcome.Timeout
                    val pct = (seq + 1).toDouble() / totalChunks
                    if (pct - lastPct >= Limits.PROGRESS_STEP || seq == totalChunks - 1) {
                        lastPct = pct
                        engine.events.transferProgress(action.id, pct)
                        // without this a cancelled transfer still streams, blocking the whole queue
                        if (store.getAction(action.id)?.status != MessageActionStatus.PENDING) return@use AckOutcome.Timeout
                    }
                }
                waiter.await(Limits.ATT_ACK_TIMEOUT_MS)
            }
        }
    }

    /**
     * Tells the receiver an offer is off, best effort. If the frame never lands the offer simply
     * stops being re-sent on reconnect, and the stale row is swept like any other.
     */
    fun withdrawOffer(transferId: String, peerIp: String) {
        engine.outbox.enqueueOfferWithdrawal(transferId, peerIp)
    }

    // --- the receiver answers ---

    /** The other side said yes: unpark the send and let the queue stream it from where they are. */
    fun onAccept(envelope: Envelope, fromIp: String): Boolean {
        val target = envelope.targetId ?: return false
        if (!store.ownsTransfer(target, fromIp, isIncoming = false)) return false
        // a redelivered accept must not resurrect a transfer somebody cancelled, nor rewind a
        // stream in flight; it may repeat itself with a newer resume point
        if (store.transferState(target)?.canApply(Wire.TransferState.ACCEPTED) != true) return false
        store.setTransferState(target, Wire.TransferState.ACCEPTED)
        val seq = maxOf(envelope.seq ?: 0, 0)
        if (seq > store.transferReceived(target)) store.setTransferReceived(target, seq)
        engine.outbox.resumeAcceptedSend(target)
        return true
    }

    fun onDecline(envelope: Envelope, fromIp: String): Boolean {
        val target = envelope.targetId ?: return false
        if (!store.ownsTransfer(target, fromIp, isIncoming = false)) return false
        // a redelivered decline must not cancel a send the user has since retried
        if (store.transferState(target)?.canMoveTo(Wire.TransferState.DECLINED) != true) return false
        val reason = envelope.reason ?: TransferManager.DECLINED
        store.setTransferState(target, Wire.TransferState.DECLINED, reason)
        // somebody chose this, so it is recorded as cancelled rather than failed: the send did not
        // go wrong, and it should not read as an error or be swept up by a bulk retry
        engine.outbox.finish(target, fromIp, MessageActionStatus.CANCELLED, reason)
        return true
    }

    /** The receiver gave up, and says why; durable, so it may arrive long after the in-memory waiter is gone. */
    fun onError(envelope: Envelope, fromIp: String): Boolean {
        val target = envelope.targetId ?: return false
        if (!store.ownsTransfer(target, fromIp, isIncoming = false)) return false
        val reason = envelope.reason ?: "refused"
        // a choice, not an error: it must not read as a failure or be swept up by a bulk retry
        val isChoice = reason == TransferManager.CANCELLED
        val next = if (isChoice) Wire.TransferState.CANCELLED else Wire.TransferState.FAILED
        // a refusal is about a send still under way: one that finished, failed or was cancelled ignores a late one
        val action = store.getAction(target)
        if (action != null && action.status != MessageActionStatus.PENDING && action.status != MessageActionStatus.WAITING) return false
        // no row for a small direct transfer; a row that already ended ignores it too
        val state = store.transferState(target)
        if (state != null && !state.canMoveTo(next)) return false
        engine.acks.complete(target, AckOutcome.Nack(reason))
        store.setTransferState(target, next, reason)
        engine.outbox.finish(target, fromIp, if (isChoice) MessageActionStatus.CANCELLED else MessageActionStatus.FAILED, reason)
        return true
    }
}

/**
 * Whether this peer is party to the transfer it is talking about, on the side the frame
 * implies. Decided from the message row: an accept, decline or error is about a message we
 * sent them, a withdrawal about one they sent us. Without it any contact who knows a transfer
 * id could decline or cancel somebody else's.
 */
internal fun Store.ownsTransfer(transferId: String, fromIp: String, isIncoming: Boolean): Boolean {
val message = getMessage(transferId) ?: return false
return message.peerIp == fromIp && (message.direction == MessageDirection.IN) == isIncoming
}

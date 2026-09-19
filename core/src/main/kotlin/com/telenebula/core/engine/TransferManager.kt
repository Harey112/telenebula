package com.telenebula.core.engine

import com.telenebula.core.CoreLog
import com.telenebula.core.CoreException
import com.telenebula.core.CorePaths
import com.telenebula.core.db.Wire
import com.telenebula.core.events.CoreEvent
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.Envelope
import com.telenebula.core.model.EnvelopeType
import com.telenebula.core.model.MessageAttachment
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageStatus
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import kotlinx.coroutines.launch

/** A reassembly in progress: the file it streams into and how far it has got. */
internal class IncomingTransfer(
    val fromIp: String,
    val name: String,
    val mime: String,
    val size: Long,
    val totalChunks: Long,
    val file: File,
    private val output: OutputStream,
    val body: String,
    val width: Long?,
    val height: Long?,
    val durationMs: Long?,
    received: Long,
) {
    @Volatile
    var received: Long = received
        private set
    /** bytes on disk, resumed from the chunk count the file was proven to hold */
    var written: Long = received * Limits.ATT_CHUNK_BYTES
        private set
    var lastPct: Double = 0.0
    @Volatile
    var lastAtMs: Long = System.currentTimeMillis()
    private var isClosed = false

    /** False when the chunk would take the file past its announced size, or is short before the last one. */
    fun accepts(bytes: ByteArray): Boolean {
        if (bytes.size > Limits.ATT_CHUNK_BYTES || written + bytes.size > size) return false
        val isLast = received == totalChunks - 1
        return isLast || bytes.size == Limits.ATT_CHUNK_BYTES
    }

    // the connection's reader writes, the housekeeper purges: the two meet only under this lock
    @Synchronized
    fun write(bytes: ByteArray) {
        if (isClosed) throw IOException("transfer released")
        output.write(bytes)
        received += 1
        written += bytes.size
        lastAtMs = System.currentTimeMillis()
    }

    @Synchronized
    fun finish() {
        isClosed = true
        output.flush()
        output.close()
    }

    @Synchronized
    fun discard() {
        isClosed = true
        runCatching { output.close() }
        file.delete()
    }

    /** Closes the handle but keeps the partial, so an interrupted transfer can still resume into it; returns the chunks it holds. */
    @Synchronized
    fun release(): Long {
        isClosed = true
        runCatching { output.flush() }
        runCatching { output.close() }
        return received
    }
}

/**
 * Chunked attachment transfers. The bytes stream between disk and socket inside the core: a file
 * only ever crosses into the app as a path, never as content, however large it is.
 */
internal class TransferManager(private val engine: Engine) {
    private val store get() = engine.store

    // --- receiving ---

    /**
     * The sender is asking before streaming something large. Nothing is reserved and no bytes move
     * until the answer goes back, which is why this wait costs nothing and needs no timeout: the
     * offer lives in the database, not in the in-flight map.
     */
    suspend fun handleOffer(envelope: Envelope, fromIp: String, link: PeerLink) {
        val name = envelope.name ?: return
        val mime = envelope.mime ?: return
        val size = envelope.size ?: return
        val totalChunks = envelope.totalChunks ?: return
        if (!isSane(size, totalChunks)) return
        if (!mayReceiveInto(envelope.id, fromIp)) return
        store.syncContact(fromIp, envelope.from.name)

        // Re-offers are expected: the sender repeats them on reconnect because it cannot know
        // whether an answer was ever delivered. Answering from the stored decision is what lets
        // the two sides converge whichever one comes back first.
        when (store.transferState(envelope.id)) {
            Wire.TransferState.ACCEPTED, Wire.TransferState.RECEIVING -> {
                // what we already hold, not zero: answering an interrupted transfer with 0 restarts
                // it from the first chunk on every reconnect, which on a flaky link never converges
                sendOfferAnswer(link, envelope.id, accept = true, reason = null, resumeFrom = store.transferReceived(envelope.id))
                return
            }
            Wire.TransferState.DECLINED -> {
                sendOfferAnswer(link, envelope.id, accept = false, reason = DECLINED, resumeFrom = 0)
                return
            }
            Wire.TransferState.COMPLETE -> {
                engine.transport.sendAck(link, envelope.id)
                return
            }
            else -> Unit
        }

        store.upsertTransfer(envelope.id, fromIp, isIncoming = true, state = Wire.TransferState.OFFERED, size = size)
        store.insertMessage(
            incomingMessage(envelope, fromIp, name, mime, size, MessageStatus.OFFERED),
        )
        // the ack is a delivery receipt for the offer, never an acceptance of it
        engine.transport.sendAck(link, envelope.id)
        engine.events.chatChanged(fromIp)
    }

    /**
     * The person answered an offer. The decision is stored before it is sent: if the frame never
     * lands, the sender's next re-offer is answered from what was stored, which is what makes a
     * wait with no timeout safe to interrupt at any point.
     */
    fun answerOffer(transferId: String, accept: Boolean, freeBytes: Long) {
        val message = store.getMessage(transferId) ?: return
        // an answer is a one-time decision, so the strict table: only an offer still open takes one
        if (store.transferState(transferId)?.canMoveTo(Wire.TransferState.ACCEPTED) != true) return

        val size = message.attachment?.size ?: 0
        // refuse rather than begin something this device cannot finish
        val noRoom = freeBytes > 0 && freeBytes - size < Limits.ATT_SPACE_RESERVE
        val accepted = accept && !noRoom
        val reason = if (noRoom) NO_SPACE else DECLINED

        if (accepted) {
            store.setTransferState(transferId, Wire.TransferState.ACCEPTED)
            // off the offer state right away: the answer is given, so the row stops asking and
            // starts reporting, even before the first chunk arrives
            store.setMessageStatus(transferId, MessageStatus.RECEIVING)
        } else {
            store.setTransferState(transferId, Wire.TransferState.DECLINED, reason)
            store.setMessageStatus(transferId, MessageStatus.DECLINED)
        }
        engine.events.messagesChanged(message.peerIp, listOf(transferId))

        // queued rather than written straight out: the sender may be away, and a decision that
        // vanishes because of that is exactly what the queue exists to prevent
        engine.outbox.enqueueOfferAnswer(
            transferId = transferId,
            peerIp = message.peerIp,
            accept = accepted,
            reason = if (accepted) null else reason,
            resumeFrom = store.transferReceived(transferId),
        )
    }

    /** The row must not sit at a percentage that will never move again. */
    fun abandonIncoming(transferId: String, fromIp: String) {
        engine.incoming.remove(transferId)?.discard()
        store.setTransferState(transferId, Wire.TransferState.CANCELLED)
        store.setTransferReceived(transferId, 0)
        store.setMessageStatus(transferId, MessageStatus.CANCELLED)
        engine.events.transferProgress(transferId, CLEAR_PROGRESS)
        engine.events.messagesChanged(fromIp, listOf(transferId))
    }

    /** Queued rather than written out, so a sender that is away still learns of it. */
    fun cancelIncoming(transferId: String) {
        val message = store.getMessage(transferId) ?: return
        if (message.direction != MessageDirection.IN) return
        // COMPLETE has a file the user can simply delete; anything already ended is left alone
        if (store.transferState(transferId)?.canMoveTo(Wire.TransferState.CANCELLED) != true) return
        engine.incoming.remove(transferId)?.discard()
        store.setTransferState(transferId, Wire.TransferState.CANCELLED, CANCELLED)
        store.setTransferReceived(transferId, 0)
        store.setMessageStatus(transferId, MessageStatus.CANCELLED)
        engine.events.transferProgress(transferId, CLEAR_PROGRESS)
        engine.events.messagesChanged(message.peerIp, listOf(transferId))
        engine.outbox.enqueueTransferError(transferId, message.peerIp, CANCELLED)
    }

    suspend fun handleBegin(envelope: Envelope, fromIp: String, link: PeerLink) {
        purgeStale()
        val name = envelope.name ?: return
        val mime = envelope.mime ?: return
        val size = envelope.size ?: return
        val totalChunks = envelope.totalChunks ?: return
        // The chunk count must match the announced size exactly. That is size-independent and
        // stricter than the old byte cap: it rejects a peer claiming a small file and then
        // streaming forever, which is what the cap was really guarding against.
        if (!isSane(size, totalChunks)) return

        // anything over the auto-accept size must have been offered and accepted first; an older
        // peer never sends one, so this can only be a peer that skipped the handshake
        if (size > Limits.ATT_AUTO_ACCEPT_BYTES && !store.transferIsAccepted(envelope.id)) {
            sendTransferError(fromIp, envelope.id, OFFER_REQUIRED)
            return
        }
        if (engine.incoming.size >= Limits.MAX_INCOMING_TRANSFERS && !engine.incoming.containsKey(envelope.id)) {
            sendTransferError(fromIp, envelope.id, BUSY)
            return
        }
        if (!mayReceiveInto(envelope.id, fromIp)) return
        // A duplicate delivery of a transfer that already finished is re-acked. The test is the
        // stored file, not the row: a row exists from the first frame, and acking on that alone
        // would confirm a transfer that never completed.
        if (store.getMessage(envelope.id)?.attachment?.uri != null) {
            engine.transport.sendAck(link, envelope.id)
            engine.events.messagesChanged(fromIp, listOf(envelope.id))
            return
        }
        engine.incoming.remove(envelope.id)?.discard()

        if (!ensureAttachmentsDir()) return
        val file = attachmentFile(envelope.id, name)

        // Resume where an interrupted transfer stopped. The saved count is only trusted when the
        // partial file is exactly that long; anything else and the file starts again, which is
        // always correct if wasteful.
        val savedChunks = store.transferReceived(envelope.id)
        val onDisk = if (file.isFile) file.length() else 0
        val resumeFrom = if (savedChunks > 0 && onDisk == savedChunks * Limits.ATT_CHUNK_BYTES) savedChunks else 0
        val output = try {
            FileOutputStream(file, resumeFrom > 0)
        } catch (e: IOException) {
            if (isOutOfSpace(e)) sendTransferError(fromIp, envelope.id, NO_SPACE)
            // no entry in the map means the chunks that follow are ignored
            return
        }

        store.upsertTransfer(envelope.id, fromIp, isIncoming = true, state = Wire.TransferState.RECEIVING, size = size)
        store.setTransferReceived(envelope.id, resumeFrom)
        store.syncContact(fromIp, envelope.from.name)
        // insert-or-ignore, so a restarted transfer reuses the row it already had
        store.insertMessage(incomingMessage(envelope, fromIp, name, mime, size, MessageStatus.RECEIVING))
        engine.events.chatChanged(fromIp)

        engine.incoming[envelope.id] = IncomingTransfer(
            fromIp = fromIp,
            name = name,
            mime = mime,
            size = size,
            totalChunks = totalChunks,
            file = file,
            output = output,
            body = envelope.body.orEmpty(),
            width = envelope.width?.takeIf { it > 0 },
            height = envelope.height?.takeIf { it > 0 },
            durationMs = envelope.duration?.takeIf { it > 0 },
            received = resumeFrom,
        )
    }

    suspend fun handleChunk(envelope: Envelope, fromIp: String, link: PeerLink) {
        val transferId = envelope.transferId ?: return
        val data = envelope.dataB64 ?: return
        val transfer = engine.incoming[transferId] ?: return
        if (transfer.fromIp != fromIp) return

        if (envelope.seq != transfer.received) {
            // TCP guarantees order — a gap means a restarted transfer broke
            engine.incoming.remove(transferId)?.discard()
            return
        }

        val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrNull()
        if (bytes == null || !transfer.accepts(bytes)) {
            // a payload we cannot decode, or one that does not add up to the announced size, is not a disk problem
            engine.incoming.remove(transferId)?.discard()
            return
        }
        try {
            transfer.write(bytes)
        } catch (e: IOException) {
            engine.incoming.remove(transferId)?.discard()
            if (isOutOfSpace(e)) sendTransferError(fromIp, transferId, NO_SPACE)
            return
        }

        if (transfer.received < transfer.totalChunks) {
            val pct = transfer.received.toDouble() / transfer.totalChunks
            if (pct - transfer.lastPct >= Limits.PROGRESS_STEP) {
                transfer.lastPct = pct
                // saved at the same granularity as the event, so a restart loses at most that
                store.setTransferReceived(transferId, transfer.received)
                engine.events.transferProgress(transferId, pct)
            }
            return
        }

        val done = engine.incoming.remove(transferId) ?: return
        if (done.written != done.size) {
            done.discard()
            return
        }
        try {
            done.finish()
        } catch (e: IOException) {
            done.discard()
            if (isOutOfSpace(e)) sendTransferError(fromIp, transferId, NO_SPACE)
            return
        }
        // the row already exists from att-begin, so this completes it rather than inserting:
        // insert-or-ignore here would silently leave it mid-transfer
        store.setMessageAttachment(
            transferId,
            MessageAttachment(
                name = done.name,
                mime = done.mime,
                size = done.size,
                uri = CorePaths.pathToUri(done.file.path),
                width = done.width,
                height = done.height,
                durationMs = done.durationMs,
            ),
        )
        store.setMessageStatus(transferId, MessageStatus.RECEIVED)
        store.setTransferState(transferId, Wire.TransferState.COMPLETE)
        // the receipt was held back while this was still arriving; if the chat was open the whole
        // time nothing else will mark this peer read again, so report it now
        engine.outbox.reportSeen(fromIp)
        // the progress row goes away now that the bubble shows the file itself
        engine.events.transferProgress(transferId, CLEAR_PROGRESS)
        store.touchContact(fromIp)
        engine.transport.sendAck(link, transferId)
        engine.events.messagesChanged(fromIp, listOf(transferId))
        val covered = store.getMessage(transferId)?.isCovered == true
        engine.inbound.notifyMessage(fromIp, "", if (covered) InboundDispatcher.COVERED_PREVIEW else done.body.ifEmpty { "Attachment" })
    }

    // --- control frames from the peer ---

    /** The sender withdrew: an unanswered offer disappears, a stream in flight is abandoned. */
    fun onCancel(envelope: Envelope, fromIp: String): Boolean {
        val target = envelope.targetId ?: return false
        if (!store.ownsTransfer(target, fromIp, isIncoming = true)) return false
        // a withdrawal is durable, so a stale one can arrive days after the file landed
        val state = store.transferState(target) ?: return false
        if (!state.canMoveTo(Wire.TransferState.CANCELLED)) return false
        if (state == Wire.TransferState.OFFERED) {
            store.setTransferState(target, Wire.TransferState.CANCELLED)
            store.deleteMessageForMe(target)
            engine.events.chatChanged(fromIp)
        } else {
            abandonIncoming(target, fromIp)
        }
        return true
    }

    /** Drops reassemblies nothing has written to for [Limits.TRANSFER_STALE_MS], releasing their file handles. */
    fun purgeStale() {
        val deadline = System.currentTimeMillis() - Limits.TRANSFER_STALE_MS
        for ((id, transfer) in engine.incoming.entries.toList()) {
            if (transfer.lastAtMs >= deadline) continue
            engine.incoming.remove(id) ?: continue
            // handleBegin only trusts the saved count when the file is exactly that long
            store.setTransferReceived(id, transfer.release())
        }
    }

    // --- files ---

    /** The layout every earlier build used: `<attachments>/<id>-<sanitized name>`; the id comes off the wire too, so it is cleaned the same way. */
    fun attachmentFile(id: String, name: String): File {
        val file = File(engine.attachmentsDir, "${CorePaths.sanitizeFileName(id)}-${CorePaths.sanitizeFileName(name)}")
        if (!CorePaths.isInside(engine.attachmentsDir, file)) throw CoreException.protocol("attachment path escapes the attachments directory")
        return file
    }

    /**
     * A transfer id is also a message id, and the peer picks it. It may only ever name a message
     * this peer sent us, or one we have never seen — never one of ours, and never another peer's
     * reassembly in flight.
     */
    private fun mayReceiveInto(transferId: String, fromIp: String): Boolean {
        engine.incoming[transferId]?.let { if (it.fromIp != fromIp) return false }
        val existing = store.getMessage(transferId) ?: return true
        return existing.direction == MessageDirection.IN && existing.peerIp == fromIp
    }

    /** Decodes a legacy inline base64 attachment onto disk; returns its file uri. */
    fun writeInlineAttachment(id: String, name: String, dataB64: String): String {
        val bytes = try {
            Base64.getDecoder().decode(dataB64)
        } catch (e: IllegalArgumentException) {
            throw CoreException.protocol("bad base64: ${e.message}")
        }
        engine.attachmentsDir.mkdirs()
        val file = attachmentFile(id, name)
        file.writeBytes(bytes)
        return CorePaths.pathToUri(file.path)
    }

    private fun ensureAttachmentsDir(): Boolean {
        if (engine.attachmentsDir.isDirectory) return true
        return try {
            engine.attachmentsDir.mkdirs()
            engine.attachmentsDir.isDirectory
        } catch (e: SecurityException) {
            CoreLog.warn(TAG, "attachments dir: ${e.message}")
            false
        }
    }

    private fun sendOfferAnswer(
        link: PeerLink,
        transferId: String,
        accept: Boolean,
        reason: String?,
        resumeFrom: Long,
    ) {
        val type = if (accept) EnvelopeType.ATT_ACCEPT else EnvelopeType.ATT_DECLINE
        engine.transport.offer(link, engine.transport.envelope(type).copy(targetId = transferId, reason = reason, seq = resumeFrom))
    }

    /**
     * Tells the sender a transfer is being abandoned, and why. Without this the sender cannot tell
     * a refusal from an offline peer: both are simply silence — which matters more now that silence
     * alone never ends anything.
     */
    private fun sendTransferError(peerIp: String, transferId: String, reason: String) {
        engine.outbox.enqueueTransferError(transferId, peerIp, reason)
    }

    private fun incomingMessage(
        envelope: Envelope,
        fromIp: String,
        name: String,
        mime: String,
        size: Long,
        status: MessageStatus,
    ) = ChatMessage(
        id = envelope.id,
        peerIp = fromIp,
        direction = MessageDirection.IN,
        body = envelope.body.orEmpty(),
        ts = if (envelope.ts > 0) envelope.ts else System.currentTimeMillis(),
        status = status,
        kind = Wire.kindForMime(mime),
        attachment = MessageAttachment(
            name = name,
            mime = mime,
            size = size,
            width = envelope.width?.takeIf { it > 0 },
            height = envelope.height?.takeIf { it > 0 },
            durationMs = envelope.duration?.takeIf { it > 0 },
        ),
        replyToId = envelope.replyToId,
        expireSecs = envelope.expiresIn?.takeIf { it in 0..Limits.MAX_EXPIRE_SECS },
        isRead = false,
        isCovered = envelope.covered == true,
    )

    private fun isSane(size: Long, totalChunks: Long): Boolean =
        size > 0 && size <= Limits.ATT_SANITY_MAX_BYTES && totalChunks == chunkCount(size)

    companion object {
        private const val TAG = "TnTransfers"

        /** a negative percentage clears the progress row on the app side */
        const val CLEAR_PROGRESS = -1.0

        /** Reason carried on an att-error when the receiving device has no room left. */
        const val NO_SPACE = "no-space"

        /** ...when a large transfer arrived without having been offered and accepted. */
        const val OFFER_REQUIRED = "offer-required"

        /** ...when too many transfers are already in flight to take another. */
        const val BUSY = "busy"

        /** Reason carried on an att-decline when the person simply said no. */
        const val DECLINED = "declined"

        /** ...when the receiver abandons a transfer it had accepted; a choice, so never a failure. */
        const val CANCELLED = "cancelled"

        fun chunkCount(size: Long): Long =
            ((size + Limits.ATT_CHUNK_BYTES - 1) / Limits.ATT_CHUNK_BYTES).coerceAtLeast(1)

        /**
         * A write that failed because the device is full rather than for any other reason. The JVM
         * does not expose errno, so this reads the message the platform put on the exception; the
         * accept path also checks the free space up front, which is the reliable half of this.
         */
        fun isOutOfSpace(e: IOException): Boolean {
            val message = e.message?.lowercase() ?: return false
            return "enospc" in message ||
                "no space left" in message ||
                "quota exceeded" in message
        }
    }
}

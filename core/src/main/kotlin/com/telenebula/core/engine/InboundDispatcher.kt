package com.telenebula.core.engine

import com.telenebula.core.db.Wire
import com.telenebula.core.events.CoreEvent
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.Contact
import com.telenebula.core.model.ContactNotificationPrefs
import com.telenebula.core.model.Envelope
import com.telenebula.core.model.EnvelopeType
import com.telenebula.core.model.MessageAttachment
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus

/** One inbound frame: who sent it, on which connection. */
internal class InboundContext(val envelope: Envelope, val fromIp: String, val link: PeerLink)

/**
 * What arrives on a connection. The peer is whoever the socket says it is, and a blocked peer gets
 * nothing at all: no storage, no ack (so its outbox eventually gives up) and no call signal
 * reaching the app.
 *
 * Every envelope type has one [Spec]: what to do, and which of the two post-steps (touching the
 * contact, acking an identified frame) apply. [specFor] is exhaustive, so a new type is a compile
 * error here rather than a frame that silently does nothing.
 */
internal class InboundDispatcher(private val engine: Engine) {
    private val store get() = engine.store

    /** [run] returns false when the frame was ignored, which skips both post-steps. */
    private class Spec(
        val touchesContact: Boolean,
        val acksById: Boolean,
        val run: suspend InboundDispatcher.(InboundContext) -> Boolean,
    )

    private val specs: Map<EnvelopeType, Spec> = EnvelopeType.entries.associateWith(::specFor)

    suspend fun handle(envelope: Envelope, fromIp: String, link: PeerLink) {
        if (fromIp.isEmpty()) return
        // after the block guard on purpose: someone we blocked must not be able to wake our queue
        if (store.isBlocked(fromIp)) return

        // anything at all from a peer is proof it is there, whatever the frame turns out to be.
        // It resets that peer's probe ladder and drains whatever is queued for it — which is how a
        // peer gone for days is served the instant it says a word, without waiting out a rung.
        engine.delivery.onPeerHeard(fromIp)

        val spec = specs.getValue(envelope.type)
        val context = InboundContext(envelope, fromIp, link)
        if (!spec.run(this, context)) return
        if (spec.touchesContact) store.touchContact(fromIp)
        if (spec.acksById && envelope.id.isNotEmpty()) engine.transport.sendAck(link, envelope.id)
    }

    private fun specFor(type: EnvelopeType): Spec = when (type) {
        EnvelopeType.HELLO -> Spec(touchesContact = true, acksById = false, run = InboundDispatcher::onHello)
        EnvelopeType.HELLO_ACK -> Spec(touchesContact = true, acksById = false, run = InboundDispatcher::onHelloAck)
        EnvelopeType.TYPING -> Spec(touchesContact = false, acksById = false, run = InboundDispatcher::onTyping)
        EnvelopeType.PING -> Spec(touchesContact = false, acksById = false, run = InboundDispatcher::onPing)
        EnvelopeType.PONG -> Spec(touchesContact = true, acksById = false, run = InboundDispatcher::onPong)
        EnvelopeType.MSG -> Spec(touchesContact = true, acksById = true, run = InboundDispatcher::onMessage)
        EnvelopeType.ATT_OFFER -> Spec(touchesContact = false, acksById = false) { engine.attachments.handleOffer(it.envelope, it.fromIp, it.link); false }
        EnvelopeType.ATT_ACCEPT -> Spec(touchesContact = false, acksById = true) { engine.sending.onAccept(it.envelope, it.fromIp) }
        EnvelopeType.ATT_DECLINE -> Spec(touchesContact = false, acksById = true) { c ->
            engine.sending.onDecline(c.envelope, c.fromIp).also { done -> if (done) announceRows(c.fromIp, c.envelope.targetId) }
        }
        EnvelopeType.ATT_CANCEL -> Spec(touchesContact = false, acksById = true) { engine.attachments.onCancel(it.envelope, it.fromIp) }
        EnvelopeType.ATT_BEGIN -> Spec(touchesContact = false, acksById = false) { engine.attachments.handleBegin(it.envelope, it.fromIp, it.link); false }
        EnvelopeType.ATT_CHUNK -> Spec(touchesContact = false, acksById = false) { engine.attachments.handleChunk(it.envelope, it.fromIp, it.link); false }
        EnvelopeType.ATT_ERROR -> Spec(touchesContact = false, acksById = true) { engine.sending.onError(it.envelope, it.fromIp) }
        EnvelopeType.REACT -> Spec(touchesContact = true, acksById = true, run = InboundDispatcher::onReact)
        EnvelopeType.EDIT -> Spec(touchesContact = true, acksById = true, run = InboundDispatcher::onEdit)
        EnvelopeType.DELETE -> Spec(touchesContact = true, acksById = true, run = InboundDispatcher::onDelete)
        EnvelopeType.SEEN -> Spec(touchesContact = true, acksById = true, run = InboundDispatcher::onSeen)
        EnvelopeType.MSG_ACK -> Spec(touchesContact = false, acksById = false, run = InboundDispatcher::onMessageAck)
        EnvelopeType.CALL_OFFER,
        EnvelopeType.CALL_RINGING,
        EnvelopeType.CALL_ANSWER,
        EnvelopeType.CALL_ICE,
        EnvelopeType.CALL_REJECT,
        EnvelopeType.CALL_END,
        EnvelopeType.CALL_RENEGOTIATE,
        EnvelopeType.CALL_RENEGOTIATE_ANSWER,
        EnvelopeType.CALL_CAM,
        -> Spec(touchesContact = true, acksById = false, run = InboundDispatcher::onCallSignal)
        EnvelopeType.UNKNOWN -> Spec(touchesContact = false, acksById = false) { false }
    }

    // --- handlers ---

    /** A frame about one message names that row; without a target the whole chat is announced. */
    private fun announceRows(ip: String, messageId: String?) {
        if (messageId == null) engine.events.chatChanged(ip) else engine.events.messagesChanged(ip, listOf(messageId))
    }

    private fun onHello(c: InboundContext): Boolean {
        store.syncContact(c.fromIp, c.envelope.from.name)
        c.envelope.app?.let { store.setClientVersion(c.fromIp, it) }
        engine.transport.offer(c.link, engine.transport.envelope(EnvelopeType.HELLO_ACK).copy(app = engine.profile.appVersion))
        engine.outbox.renewPendingOffers(c.fromIp)
        engine.events.chatChanged(c.fromIp)
        return true
    }

    private fun onHelloAck(c: InboundContext): Boolean {
        c.envelope.app?.let { store.setClientVersion(c.fromIp, it) }
        engine.outbox.renewPendingOffers(c.fromIp)
        engine.events.chatChanged(c.fromIp)
        return true
    }

    private fun onTyping(c: InboundContext): Boolean {
        engine.events.emit(CoreEvent.Typing(c.fromIp, c.envelope.typing == true))
        return true
    }

    private fun onPing(c: InboundContext): Boolean {
        val pong = engine.transport.envelope(EnvelopeType.PONG).copy(presence = PresenceWire.encode(engine.isOnline.get()))
        engine.transport.offer(c.link, pong)
        return true
    }

    private fun onPong(c: InboundContext): Boolean {
        engine.acks.complete(Transport.pongKey(c.fromIp), AckOutcome.Ack)
        engine.events.emit(CoreEvent.PresenceChanged(c.fromIp, PresenceWire.decode(c.envelope.presence)))
        engine.events.contactChanged(c.fromIp)
        return true
    }

    private fun onMessage(c: InboundContext): Boolean {
        val body = c.envelope.body ?: return false
        if (c.envelope.id.isEmpty()) return false
        store.syncContact(c.fromIp, c.envelope.from.name)
        val isNew = storeIncoming(c.envelope, c.fromIp)
        engine.events.chatChanged(c.fromIp)
        // only a message we had not seen may raise a notification: a queue that re-sends until it
        // is acked will redeliver one whose ack was lost, and re-notifying for it every time the
        // peer reappears would be the queue shouting at the user
        if (isNew) notifyMessage(c.fromIp, c.envelope.from.name, body)
        return true
    }

    /** Acked even when the target is unknown so the sender stops retrying; set and remove are absolute, so redelivery is harmless. */
    private fun onReact(c: InboundContext): Boolean {
        val targetId = c.envelope.targetId
        if (targetId != null) {
            val target = store.getMessage(targetId)?.takeIf { it.peerIp == c.fromIp }
            if (target != null) {
                if (c.envelope.remove == true) {
                    store.removeReaction(targetId, c.fromIp)
                } else {
                    val emoji = c.envelope.emoji
                    if (emoji != null && store.setReaction(targetId, c.fromIp, emoji) && target.direction == MessageDirection.OUT) {
                        notifyReaction(c.fromIp, c.envelope.from.name, emoji, previewOf(target))
                    }
                }
            }
        }
        announceRows(c.fromIp, c.envelope.targetId)
        return true
    }

    private fun onEdit(c: InboundContext): Boolean {
        val targetId = c.envelope.targetId
        val newBody = c.envelope.newBody
        // only the author may edit: the target must be an incoming message from this peer
        val isEdited = targetId != null && newBody != null && store.applyEdit(targetId, newBody, c.fromIp)
        announceRows(c.fromIp, targetId)
        if (isEdited) engine.outbox.reportSeen(c.fromIp)
        return true
    }

    private fun onDelete(c: InboundContext): Boolean {
        c.envelope.targetId?.let { store.applyDeleteForEveryone(it, c.fromIp) }
        announceRows(c.fromIp, c.envelope.targetId)
        return true
    }

    private fun onSeen(c: InboundContext): Boolean {
        val ids = c.envelope.targetIds?.take(Limits.MAX_SEEN_IDS)
        if (ids != null && store.setSeenByPeer(ids, c.fromIp)) engine.events.messagesChanged(c.fromIp, ids)
        return true
    }

    private fun onMessageAck(c: InboundContext): Boolean {
        val ackId = c.envelope.ackId ?: return false
        engine.acks.complete(ackId, AckOutcome.Ack)
        engine.events.summariesChanged()
        return true
    }

    private fun onCallSignal(c: InboundContext): Boolean {
        engine.events.emit(CoreEvent.Signal(c.fromIp, c.envelope))
        return true
    }

    /**
     * Persists an incoming message; a legacy inline attachment lands on disk on the way in.
     * False when the message was already there, so a redelivery stays silent.
     */
    private fun storeIncoming(envelope: Envelope, fromIp: String): Boolean {
        // a redelivery must not rewrite the attachment file it already produced
        if (store.getMessage(envelope.id) != null) return false
        var attachment: MessageAttachment? = null
        var kind = MessageKind.TEXT
        val inline = envelope.attachment
        if (inline != null) {
            val path = runCatching {
                engine.attachments.writeInlineAttachment(envelope.id, inline.name, inline.dataB64)
            }.getOrNull()
            // out of space or a bad payload — keep the message as text only
            if (path != null) {
                kind = Wire.kindForMime(inline.mime)
                attachment = MessageAttachment(
                    name = inline.name,
                    mime = inline.mime,
                    size = inline.size,
                    uri = path,
                )
            }
        }
        return store.insertMessage(
            ChatMessage(
                id = envelope.id,
                peerIp = fromIp,
                direction = MessageDirection.IN,
                body = envelope.body.orEmpty(),
                ts = if (envelope.ts > 0) envelope.ts else System.currentTimeMillis(),
                status = MessageStatus.RECEIVED,
                kind = kind,
                attachment = attachment,
                replyToId = envelope.replyToId,
                expireSecs = envelope.expiresIn?.takeIf { it in 0..Limits.MAX_EXPIRE_SECS },
                isRead = false,
            ),
        )
    }

    private fun previewOf(message: ChatMessage): String =
        message.body.ifEmpty { message.attachment?.name ?: "Attachment" }

    fun notifyMessage(fromIp: String, announcedName: String, preview: String) {
        val context = notificationContext(fromIp, announcedName)
        engine.events.emit(
            CoreEvent.MessageReceived(
                ip = fromIp,
                name = context.name,
                preview = preview,
                isMuted = context.isMuted,
                notifications = context.notifications,
            ),
        )
    }

    private fun notifyReaction(fromIp: String, announcedName: String, emoji: String, preview: String) {
        val context = notificationContext(fromIp, announcedName)
        engine.events.emit(
            CoreEvent.ReactionReceived(
                ip = fromIp,
                name = context.name,
                emoji = emoji,
                preview = preview,
                isMuted = context.isMuted,
                notifications = context.notifications,
            ),
        )
    }

    /** Display name, mute state and per-contact overrides, as a notification needs them. */
    fun notificationContext(fromIp: String, announcedName: String): NotificationContext {
        val contact = runCatching { engine.store.getContact(fromIp) }.getOrNull()
        val name = contact?.displayName()?.takeIf { it.isNotEmpty() }
            ?: announcedName.ifEmpty { fromIp }
        return NotificationContext(
            name = name,
            isMuted = contact != null && isMutedAt(contact.muteUntil, System.currentTimeMillis()),
            notifications = contact?.notifications,
        )
    }

    private fun Contact.displayName(): String = nickname.ifEmpty { name }

    internal class NotificationContext(
        val name: String,
        val isMuted: Boolean,
        val notifications: ContactNotificationPrefs?,
    )

    companion object {
        /** mute_until: 0 = not muted, -1 = muted forever, else epoch ms */
        const val MUTE_FOREVER = -1L

        fun isMutedAt(muteUntil: Long, nowMs: Long): Boolean = muteUntil == MUTE_FOREVER || muteUntil > nowMs
    }
}

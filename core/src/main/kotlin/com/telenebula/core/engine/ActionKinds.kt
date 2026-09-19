package com.telenebula.core.engine

import com.telenebula.core.db.Store
import com.telenebula.core.model.AttachmentPayload
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.Envelope
import com.telenebula.core.model.EnvelopeType
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionType

/**
 * What one action type does at its three moments: locally when it is queued or retried, on the
 * wire when it runs, and locally again when it is cancelled. Adding a type is one entry here
 * instead of an arm in each of five `when`s that had to agree.
 */
internal sealed interface ActionKind {
    /** the frame type this action becomes; null for a type this build cannot run */
    val envelopeType: EnvelopeType?

    fun applyLocal(store: Store, action: MessageAction, myIp: String) = Unit

    fun revertLocal(store: Store, action: MessageAction, myIp: String) = Unit

    /** The frame, from the stamped base envelope; null when the action carries nothing sendable. */
    fun envelope(base: Envelope, action: MessageAction, message: ChatMessage): Envelope? = null

    object Send : ActionKind {
        override val envelopeType get() = EnvelopeType.MSG

        override fun envelope(base: Envelope, action: MessageAction, message: ChatMessage): Envelope? {
            val attachment = message.attachment
            // file-backed attachments go through the chunked path; only a small legacy inline blob may still travel in one frame
            val inline = if (attachment == null) {
                null
            } else {
                val data = attachment.dataB64 ?: return null
                if (data.length > Limits.MAX_INLINE_B64) return null
                AttachmentPayload(name = attachment.name, mime = attachment.mime, size = attachment.size, dataB64 = data)
            }
            return base.copy(
                id = message.id,
                body = message.body,
                ts = message.ts,
                replyToId = message.replyToId,
                expiresIn = message.expireSecs,
                attachment = inline,
                cover = message.cover,
            )
        }
    }

    object React : ActionKind {
        override val envelopeType get() = EnvelopeType.REACT

        override fun applyLocal(store: Store, action: MessageAction, myIp: String) {
            if (action.payload.remove == true) store.removeReaction(action.messageId, myIp)
            else action.payload.emoji?.let { store.setReaction(action.messageId, myIp, it) }
        }

        /** a cancelled removal restores the reaction */
        override fun revertLocal(store: Store, action: MessageAction, myIp: String) {
            if (action.payload.remove == true) action.payload.emoji?.let { store.setReaction(action.messageId, myIp, it) }
            else store.removeReaction(action.messageId, myIp)
        }

        override fun envelope(base: Envelope, action: MessageAction, message: ChatMessage): Envelope? {
            if (action.payload.emoji == null && action.payload.remove != true) return null
            return base.copy(
                id = action.id,
                targetId = message.id,
                emoji = action.payload.emoji,
                remove = if (action.payload.remove == true) true else null,
            )
        }
    }

    object Edit : ActionKind {
        override val envelopeType get() = EnvelopeType.EDIT

        override fun applyLocal(store: Store, action: MessageAction, myIp: String) {
            action.payload.body?.let { store.applyEdit(action.messageId, it, null) }
        }

        override fun revertLocal(store: Store, action: MessageAction, myIp: String) {
            action.payload.prevBody?.let { store.restoreBody(action.messageId, it, action.payload.prevEdited == true) }
        }

        override fun envelope(base: Envelope, action: MessageAction, message: ChatMessage): Envelope? {
            val body = action.payload.body ?: return null
            return base.copy(id = action.id, targetId = message.id, newBody = body)
        }
    }

    object Delete : ActionKind {
        override val envelopeType get() = EnvelopeType.DELETE

        override fun applyLocal(store: Store, action: MessageAction, myIp: String) = store.markDeleted(action.messageId)

        override fun revertLocal(store: Store, action: MessageAction, myIp: String) = store.unmarkDeleted(action.messageId)

        override fun envelope(base: Envelope, action: MessageAction, message: ChatMessage): Envelope = base.copy(id = action.id, targetId = message.id)
    }

    object Seen : ActionKind {
        override val envelopeType get() = EnvelopeType.SEEN

        /** or the frame the retry triggers would carry nothing */
        override fun applyLocal(store: Store, action: MessageAction, myIp: String) = store.markSeenUnreported(action.messageId)

        /** the next batch reads the flags, not the rows, and would carry it anyway */
        override fun revertLocal(store: Store, action: MessageAction, myIp: String) = store.markSeenReported(listOf(action.messageId))
    }

    /** An answer to an attachment offer; the frame is built from the payload alone, by the sender. */
    class TransferControl(override val envelopeType: EnvelopeType) : ActionKind

    object Unknown : ActionKind {
        override val envelopeType: EnvelopeType? get() = null
    }
}

internal val MessageActionType.kind: ActionKind
    get() = when (this) {
        MessageActionType.SEND -> ActionKind.Send
        MessageActionType.REACT -> ActionKind.React
        MessageActionType.EDIT -> ActionKind.Edit
        MessageActionType.DELETE -> ActionKind.Delete
        MessageActionType.SEEN -> ActionKind.Seen
        MessageActionType.ATT_ACCEPT -> ACCEPT
        MessageActionType.ATT_DECLINE -> DECLINE
        MessageActionType.ATT_CANCEL -> CANCEL
        MessageActionType.ATT_ERROR -> ERROR
        MessageActionType.UNKNOWN -> ActionKind.Unknown
    }

private val ACCEPT = ActionKind.TransferControl(EnvelopeType.ATT_ACCEPT)
private val DECLINE = ActionKind.TransferControl(EnvelopeType.ATT_DECLINE)
private val CANCEL = ActionKind.TransferControl(EnvelopeType.ATT_CANCEL)
private val ERROR = ActionKind.TransferControl(EnvelopeType.ATT_ERROR)

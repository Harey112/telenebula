package com.telenebula.core.db

import com.telenebula.core.model.CallOutcome
import com.telenebula.core.model.CoverRevealGate
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus

/**
 * The strings these enums have always been stored (and sent) as. They are written out rather than
 * derived from the serial names so that a value the database holds but this build does not know
 * falls back to something renderable instead of failing the whole query — a chat that empties
 * itself because one row carries an unexpected status is the worst outcome here.
 */
internal object Wire {
    val MessageDirection.wire: String get() = if (this == MessageDirection.OUT) "out" else "in"

    fun direction(value: String?): MessageDirection =
        if (value == "out") MessageDirection.OUT else MessageDirection.IN

    val MessageStatus.wire: String
        get() = when (this) {
            MessageStatus.PENDING -> "pending"
            MessageStatus.SENT -> "sent"
            MessageStatus.DELIVERED -> "delivered"
            MessageStatus.OFFERED -> "offered"
            MessageStatus.DECLINED -> "declined"
            MessageStatus.CANCELLED -> "cancelled"
            MessageStatus.RECEIVING -> "receiving"
            MessageStatus.RECEIVED -> "received"
        }

    fun status(value: String?, direction: MessageDirection): MessageStatus = when (value) {
        "pending" -> MessageStatus.PENDING
        "sent" -> MessageStatus.SENT
        "delivered" -> MessageStatus.DELIVERED
        "offered" -> MessageStatus.OFFERED
        "declined" -> MessageStatus.DECLINED
        "cancelled" -> MessageStatus.CANCELLED
        "receiving" -> MessageStatus.RECEIVING
        "received" -> MessageStatus.RECEIVED
        // written by a newer build than this one: show it as finished rather than dropping the row
        else -> if (direction == MessageDirection.OUT) MessageStatus.SENT else MessageStatus.RECEIVED
    }

    val CoverRevealGate.wire: String
        get() = when (this) {
            CoverRevealGate.TAP -> "tap"
            CoverRevealGate.ASK -> "ask"
            CoverRevealGate.CODE -> "code"
            CoverRevealGate.DEVICE -> "device"
        }

    /** null is "follow the global setting", which is also where a value a newer build wrote lands. */
    fun revealGate(value: String?): CoverRevealGate? = when (value) {
        "tap" -> CoverRevealGate.TAP
        "ask" -> CoverRevealGate.ASK
        "code" -> CoverRevealGate.CODE
        "device" -> CoverRevealGate.DEVICE
        else -> null
    }

    val MessageKind.wire: String
        get() = when (this) {
            MessageKind.TEXT -> "text"
            MessageKind.IMAGE -> "image"
            MessageKind.VIDEO -> "video"
            MessageKind.FILE -> "file"
        }

    fun kind(value: String?): MessageKind = when (value) {
        "image" -> MessageKind.IMAGE
        "video" -> MessageKind.VIDEO
        "file" -> MessageKind.FILE
        else -> MessageKind.TEXT
    }

    fun kindForMime(mime: String): MessageKind = when {
        mime.startsWith("image/") -> MessageKind.IMAGE
        mime.startsWith("video/") -> MessageKind.VIDEO
        else -> MessageKind.FILE
    }

    val MessageActionType.wire: String
        get() = when (this) {
            MessageActionType.SEND -> "send"
            MessageActionType.REACT -> "react"
            MessageActionType.EDIT -> "edit"
            MessageActionType.DELETE -> "delete"
            MessageActionType.SEEN -> "seen"
            MessageActionType.ATT_ACCEPT -> "att-accept"
            MessageActionType.ATT_DECLINE -> "att-decline"
            MessageActionType.ATT_CANCEL -> "att-cancel"
            MessageActionType.ATT_ERROR -> "att-error"
            // never written: the CHECK would reject it, and an unrunnable row has nothing to store
            MessageActionType.UNKNOWN -> "unknown"
        }

    /**
     * An unrecognised type decodes to [MessageActionType.UNKNOWN], never to SEND. A row a newer
     * build wrote must stay renderable, but guessing "send" for it would put a message on the wire
     * that nobody asked for.
     */
    fun actionType(value: String?): MessageActionType = when (value) {
        "send" -> MessageActionType.SEND
        "react" -> MessageActionType.REACT
        "edit" -> MessageActionType.EDIT
        "delete" -> MessageActionType.DELETE
        "seen" -> MessageActionType.SEEN
        "att-accept" -> MessageActionType.ATT_ACCEPT
        "att-decline" -> MessageActionType.ATT_DECLINE
        "att-cancel" -> MessageActionType.ATT_CANCEL
        "att-error" -> MessageActionType.ATT_ERROR
        else -> MessageActionType.UNKNOWN
    }

    val MessageActionStatus.wire: String
        get() = when (this) {
            MessageActionStatus.PENDING -> "pending"
            MessageActionStatus.WAITING -> "waiting"
            MessageActionStatus.SUCCESS -> "success"
            MessageActionStatus.FAILED -> "failed"
            MessageActionStatus.CANCELLED -> "cancelled"
        }

    fun actionStatus(value: String?): MessageActionStatus = when (value) {
        "waiting" -> MessageActionStatus.WAITING
        "success" -> MessageActionStatus.SUCCESS
        "failed" -> MessageActionStatus.FAILED
        "cancelled" -> MessageActionStatus.CANCELLED
        else -> MessageActionStatus.PENDING
    }

    val CallOutcome.wire: String
        get() = when (this) {
            CallOutcome.ANSWERED -> "answered"
            CallOutcome.MISSED -> "missed"
            CallOutcome.DECLINED -> "declined"
            CallOutcome.NO_ANSWER -> "no-answer"
            CallOutcome.UNREACHABLE -> "unreachable"
            CallOutcome.CANCELLED -> "cancelled"
            CallOutcome.FAILED -> "failed"
        }

    fun callOutcome(value: String?): CallOutcome = when (value) {
        "answered" -> CallOutcome.ANSWERED
        "missed" -> CallOutcome.MISSED
        "declined" -> CallOutcome.DECLINED
        "no-answer" -> CallOutcome.NO_ANSWER
        "unreachable" -> CallOutcome.UNREACHABLE
        "cancelled" -> CallOutcome.CANCELLED
        else -> CallOutcome.FAILED
    }

    /** The state machine of `attachment_transfers.state`. */
    enum class TransferState(val wire: String) {
        OFFERED("offered"),
        ACCEPTED("accepted"),
        RECEIVING("receiving"),
        COMPLETE("complete"),
        DECLINED("declined"),
        FAILED("failed"),
        CANCELLED("cancelled"),
        ;

        val isOpen: Boolean get() = this == OFFERED || this == ACCEPTED || this == RECEIVING

        /** The one table every transition is checked against; a finished transfer moves nowhere. */
        fun canMoveTo(next: TransferState): Boolean = when (this) {
            OFFERED -> next == ACCEPTED || next == RECEIVING || next == DECLINED || next == CANCELLED || next == FAILED
            ACCEPTED -> next == RECEIVING || next == CANCELLED || next == FAILED
            RECEIVING -> next == COMPLETE || next == CANCELLED || next == FAILED
            COMPLETE, DECLINED, FAILED, CANCELLED -> false
        }

        /** A redelivered inbound frame may re-apply the open state it already produced (an accept carrying a newer resume point). */
        fun canApply(next: TransferState): Boolean = (this == next && isOpen) || canMoveTo(next)

        companion object {
            fun of(value: String?): TransferState? = entries.firstOrNull { it.wire == value }
        }
    }
}

package com.telenebula.app.platform

import com.telenebula.core.model.Contact
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionStatus

/** mute_until sentinel shared with the core */
const val MUTE_FOREVER = -1L

object ContactLabels {
    fun isMuted(muteUntil: Long, now: Long = System.currentTimeMillis()): Boolean = muteUntil == MUTE_FOREVER || muteUntil > now

    fun isOverlayIp(value: String): Boolean {
        val text = value.trim()
        if (text.isEmpty() || text.contains(":::")) return false
        val halves = text.split("::")
        if (halves.size > 2) return false
        val groups = halves.flatMap { half -> if (half.isEmpty()) emptyList() else half.split(':') }
        if (groups.any { group -> group.length !in 1..4 || group.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' } }) return false
        if (halves.size == 1 && groups.size != 8 || halves.size == 2 && groups.size > 7) return false
        return groups.any { it.toInt(16) != 0 }
    }

    /** Chats and calls: the user's own word for the peer comes first. */
    fun chatLabel(contact: Contact): String = contact.nickname.ifEmpty { contact.name.ifEmpty { contact.ip } }

    /** Contacts screens: the real username once the peer has announced it; the nickname until then. */
    fun contactLabel(contact: Contact): String = contact.name.ifEmpty { contact.nickname.ifEmpty { contact.ip } }
}

object ActionQueue {
    /**
     * Strict per-message sequencing: the running action is the first one (chronologically) that is
     * not finished, and only if it is pending. A failed action stalls the queue. [actions] must be
     * ordered by createdAt ascending, as the db returns them.
     */
    fun runningActionId(actions: List<MessageAction>): String? {
        for (action in actions) {
            if (action.status == MessageActionStatus.SUCCESS || action.status == MessageActionStatus.CANCELLED) continue
            return if (action.status == MessageActionStatus.PENDING) action.id else null
        }
        return null
    }
}

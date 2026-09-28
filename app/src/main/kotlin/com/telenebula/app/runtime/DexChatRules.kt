package com.telenebula.app.runtime

import com.telenebula.app.platform.ContactLabels
import com.telenebula.core.CoreClient
import com.telenebula.core.model.Contact

/** What the phone's chat screen permits, enforced where a browser's command arrives, so a stale page cannot do more. */
object DexChatRules {
    fun sendRefusal(contact: Contact?): String? = when {
        contact?.isArchived == true -> "This chat is archived. Unarchive it to send."
        contact?.isBlocked == true -> "You blocked this contact. Unblock them to send."
        else -> null
    }

    fun editRefusal(contact: Contact?): String? =
        if (contact?.isArchived == true) "This chat is archived. Unarchive it to edit a message." else null

    fun callRefusal(contact: Contact?, isTunnelOn: Boolean): String? = when {
        contact?.isArchived == true -> "This chat is archived. Unarchive it to call."
        contact?.isBlocked == true -> "Contact is blocked: Unblock them to call."
        !isTunnelOn -> "The Nebula tunnel is off. Turn it on to call."
        else -> null
    }

    /** The address a new contact is saved under, or the reason it cannot be; [isSaved] must answer for the normalized address. */
    fun newContactAddress(raw: String, ownIp: String?, isSaved: Boolean): Result<String> {
        val ip = CoreClient.normalizeIp(raw)
        return when {
            ip.isEmpty() -> Result.failure(IllegalArgumentException("Enter their Nebula IPv6 number."))
            !ContactLabels.isOverlayIp(ip) -> Result.failure(IllegalArgumentException("That is not a Nebula IPv6 number."))
            ip == ownIp -> Result.failure(IllegalArgumentException("That is this phone's own address."))
            isSaved -> Result.failure(IllegalArgumentException("A contact is already saved at $ip."))
            else -> Result.success(ip)
        }
    }
}

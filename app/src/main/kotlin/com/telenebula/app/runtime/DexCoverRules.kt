package com.telenebula.app.runtime

import com.telenebula.core.model.ChatMessage
import com.telenebula.dex.wire.DexMessage
import com.telenebula.dex.wire.DexMessageKind
import com.telenebula.dex.wire.DexRevealGate

/** What a browser gets of a covered message, on every read path, from the chat's effective gate. */
object DexCoverRules {
    /** Behind a code or the phone's lock nothing of it crosses the wire; tap and ask are a cover drawn in the page, as on the phone. */
    fun isWithheld(isCovered: Boolean, gate: DexRevealGate): Boolean = isCovered && !DexProfiles.canRevealInBrowser(gate)

    /** The message as the chat view and its older pages send it; a withheld one keeps its place and its cover, nothing else. */
    fun shown(message: DexMessage, gate: DexRevealGate): DexMessage =
        if (isWithheld(message.isCovered, gate)) message.copy(body = "", att = null, kind = DexMessageKind.TEXT) else message

    /** A result list or a media grid shows content outside any cover, so a covered message is never in one. */
    fun listed(messages: List<ChatMessage>): List<ChatMessage> = messages.filterNot { it.isCovered }
}

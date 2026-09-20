package com.telenebula.core.events

import com.telenebula.core.model.ContactNotificationPrefs
import com.telenebula.core.model.Envelope
import com.telenebula.core.model.PeerPresence
import com.telenebula.core.model.PeerQueueState

/** What the engine announces, plus the tunnel toggle the service notification offers. */
sealed interface CoreEvent {
    /** null [messageIds]: rows were added or removed, read the chat again; a set: only those rows changed */
    data class ChatChanged(val ip: String, val messageIds: Set<String>? = null, val hasContactChange: Boolean = false) : CoreEvent

    data object SummariesChanged : CoreEvent

    data object CallLogsChanged : CoreEvent

    /** Something inside the engine failed with no caller to throw to; the client turns it into a notice. */
    data class EngineFault(val what: String, val message: String) : CoreEvent

    /** pct in [0, 1]; negative clears the entry */
    data class TransferProgress(val actionId: String, val pct: Double) : CoreEvent

    /** a call signalling envelope, for whoever runs the calls */
    data class Signal(val fromIp: String, val envelope: Envelope) : CoreEvent

    data class MessageReceived(
        val ip: String,
        val name: String,
        val preview: String,
        val isMuted: Boolean,
        val notifications: ContactNotificationPrefs?,
    ) : CoreEvent

    /** The other side answered a file of ours: declined it, had no room, or stopped receiving it. */
    data class TransferOutcome(
        val ip: String,
        val name: String,
        /** what happened, without the file: "Declined your file" */
        val summary: String,
        val fileName: String,
        val isMuted: Boolean,
        val notifications: ContactNotificationPrefs?,
    ) : CoreEvent

    data class ReactionReceived(
        val ip: String,
        val name: String,
        val emoji: String,
        val preview: String,
        val isMuted: Boolean,
        val notifications: ContactNotificationPrefs?,
    ) : CoreEvent

    data class Typing(val fromIp: String, val isTyping: Boolean) : CoreEvent

    /** what a peer's pong said, or OFFLINE when a probe went unanswered */
    data class PresenceChanged(val ip: String, val presence: PeerPresence) : CoreEvent

    /**
     * One peer's outbound queue: how much is waiting, whether it is moving, when it is next tried.
     * Pure state — it invalidates no query, because nothing about it changes what a read returns.
     */
    data class PeerQueue(val state: PeerQueueState) : CoreEvent

    /** the Connect/Disconnect action on the background service notification */
    data object TunnelToggle : CoreEvent
}

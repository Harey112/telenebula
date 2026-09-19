package com.telenebula.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LighthouseEntry(val nebulaIp: String, val underlay: String)

@Serializable
data class Profile(
    val certName: String,
    val overlayIp: String,
    val networks: List<String> = emptyList(),
    val caPem: String,
    val certPem: String,
    val certFingerprint: String = "",
    val certNotAfter: String = "",
    val lighthouses: List<LighthouseEntry> = emptyList(),
    val listenPort: Int,
    val msgPort: Int,
    /**
     * Every other nebula option. Each field carries its own default, so a profile written before
     * an option existed (or before any of them were editable) decodes with the defaults filled in.
     */
    val nebula: NebulaAdvancedConfig = NebulaAdvancedConfig(),
)

@Serializable
data class Contact(
    val ip: String,
    val name: String,
    val nickname: String = "",
    val notes: String = "",
    val addedAt: Long,
    val lastSeenAt: Long? = null,
    val pinnedAt: Long? = null,
    val isArchived: Boolean = false,
    val isBlocked: Boolean = false,
    /** 0 = not muted, -1 = forever, else epoch ms */
    val muteUntil: Long = 0,
    val isMarkedUnread: Boolean = false,
    val notifications: ContactNotificationPrefs? = null,
    val clientVersion: String? = null,
    /** disappearing-message timer for messages we send in this chat (0 = off) */
    val disappearSeconds: Int = 0,
    val privacy: ContactPrivacyPrefs = ContactPrivacyPrefs(),
)

/** Per-contact privacy; null means the global setting applies. */
@Serializable
data class ContactPrivacyPrefs(
    val sendReadReceipts: Boolean? = null,
    val sendTypingIndicators: Boolean? = null,
    val blockScreenshots: Boolean? = null,
    val revealGate: CoverRevealGate? = null,
)

/** What this device asks for before a covered message is shown; local, never sent. */
@Serializable
enum class CoverRevealGate {
    @SerialName("tap") TAP,
    @SerialName("ask") ASK,
    @SerialName("code") CODE,
    @SerialName("device") DEVICE,
}

/** Absent (null) fields are left untouched by the core. */
@Serializable
data class ContactFlagsPatch(
    val isPinned: Boolean? = null,
    val isArchived: Boolean? = null,
    val isBlocked: Boolean? = null,
    val muteUntil: Long? = null,
    val isMarkedUnread: Boolean? = null,
    val disappearSeconds: Int? = null,
)

@Serializable
enum class MessageDirection {
    @SerialName("in") IN,
    @SerialName("out") OUT,
}

@Serializable
enum class MessageStatus {
    @SerialName("pending") PENDING,
    @SerialName("sent") SENT,
    @SerialName("delivered") DELIVERED,
    /** incoming, offered but not yet answered: nothing has been transferred */
    @SerialName("offered") OFFERED,
    /** incoming, offer turned down; no bytes were ever moved */
    @SerialName("declined") DECLINED,
    /** a transfer either side abandoned after it was accepted; the partial file is gone */
    @SerialName("cancelled") CANCELLED,
    /** incoming, still arriving: the row exists so progress has something to attach to */
    @SerialName("receiving") RECEIVING,
    @SerialName("received") RECEIVED,
    ;

    /** No file ever landed, so there is nothing to open, quote, copy or pass on. */
    val hasNoFile: Boolean get() = this == DECLINED || this == CANCELLED
}

@Serializable
enum class MessageKind {
    @SerialName("text") TEXT,
    @SerialName("image") IMAGE,
    @SerialName("video") VIDEO,
    @SerialName("file") FILE,
}

@Serializable
data class MessageAttachment(
    val name: String,
    val mime: String,
    val size: Long,
    /** local file path (attachments live on disk, not in the db) */
    val uri: String? = null,
    /** legacy inline storage from before file-backed attachments */
    val dataB64: String? = null,
    /** pixel size of image and video, so a bubble can reserve its shape before the file decodes */
    val width: Long? = null,
    val height: Long? = null,
    /** audio length, so a voice clip shows its length before it is played */
    val durationMs: Long? = null,
)

@Serializable
data class ChatMessage(
    val id: String,
    val peerIp: String,
    val direction: MessageDirection,
    val body: String,
    val ts: Long,
    val status: MessageStatus,
    val kind: MessageKind,
    val attachment: MessageAttachment? = null,
    val isEdited: Boolean = false,
    val isDeleted: Boolean = false,
    /** overlay ip → emoji */
    val reactions: Map<String, String> = emptyMap(),
    val replyToId: String? = null,
    val seenAt: Long? = null,
    /** read locally; always true for outgoing. Absent from an older core, which reads as read. */
    val isRead: Boolean = true,
    val expireSecs: Long? = null,
    val expiresAt: Long? = null,
    /** the whole message is behind a lock until it is revealed on this device */
    val isCovered: Boolean = false,
)

@Serializable
enum class MessageActionType {
    @SerialName("send") SEND,
    @SerialName("react") REACT,
    @SerialName("edit") EDIT,
    @SerialName("delete") DELETE,
    @SerialName("seen") SEEN,

    /** the four answers to an attachment offer; durable, so a decision survives the other side being away */
    @SerialName("att-accept") ATT_ACCEPT,
    @SerialName("att-decline") ATT_DECLINE,
    @SerialName("att-cancel") ATT_CANCEL,
    @SerialName("att-error") ATT_ERROR,

    /** a type a newer build wrote: renderable, never runnable, never mistaken for a send */
    @SerialName("unknown") UNKNOWN,
    ;

    /** Control frames answer an offer rather than act on a message of their own. */
    val isTransferControl: Boolean
        get() = this == ATT_ACCEPT || this == ATT_DECLINE || this == ATT_CANCEL || this == ATT_ERROR
}

@Serializable
enum class MessageActionStatus {
    @SerialName("pending") PENDING,
    /** an offer sent, nothing to do until the peer answers; the outbox does not pick these up */
    @SerialName("waiting") WAITING,
    @SerialName("success") SUCCESS,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
data class MessageActionPayload(
    val emoji: String? = null,
    val remove: Boolean? = null,
    val body: String? = null,
    val prevBody: String? = null,
    val prevEdited: Boolean? = null,
    /** why it gave up, when the peer said so rather than simply going quiet */
    val failReason: String? = null,
    /** att-*: the transfer this frame answers; the envelope carries it as targetId */
    val transferId: String? = null,
    /** att-accept: the chunk the receiver already holds, so the stream picks up from there */
    val resumeFrom: Long? = null,
    /** att-decline / att-error: declined, no-space, offer-required, busy */
    val reason: String? = null,
)

@Serializable
data class MessageAction(
    val id: String,
    val messageId: String,
    val peerIp: String,
    val type: MessageActionType,
    val payload: MessageActionPayload = MessageActionPayload(),
    val status: MessageActionStatus,
    /**
     * Probes are counted per peer, not per action, so nothing bumps this during a drain any more.
     * The column is inherited and stays; the field is what a restored backup still carries.
     */
    val attempts: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ChatSummary(
    val ip: String,
    val name: String,
    val lastBody: String? = null,
    val lastTs: Long? = null,
    val lastDirection: MessageDirection? = null,
    val lastStatus: MessageStatus? = null,
    /**
     * Status of the last message's own send action. Null for an incoming last message, and for
     * rows predating the outbox. [MessageStatus] alone cannot tell these apart: a send that failed
     * or was cancelled leaves the message itself `pending` forever.
     */
    val lastSendStatus: MessageActionStatus? = null,
    val lastSeenAt: Long? = null,
    val unread: Int = 0,
    val pinnedAt: Long? = null,
    val isArchived: Boolean = false,
    val isBlocked: Boolean = false,
    val muteUntil: Long = 0,
    val isMarkedUnread: Boolean = false,
)

@Serializable
enum class CallOutcome {
    @SerialName("answered") ANSWERED,
    @SerialName("missed") MISSED,
    @SerialName("declined") DECLINED,
    @SerialName("no-answer") NO_ANSWER,
    @SerialName("unreachable") UNREACHABLE,
    @SerialName("cancelled") CANCELLED,
    @SerialName("failed") FAILED,
}

@Serializable
data class CallLog(
    val id: String,
    val peerIp: String,
    val direction: MessageDirection,
    val isVideo: Boolean,
    val outcome: CallOutcome,
    val startedAt: Long,
    val connectedAt: Long? = null,
    val endedAt: Long,
)

/** Written into every backup archive, so a restore knows what it is reading. */
@Serializable
data class BackupManifest(
    val format: Int,
    val createdAt: Long,
    val appVersion: String,
    /**
     * The attachments directory of the device that wrote the backup; paths inside the database
     * point there and are rewritten on restore.
     */
    val attachmentsDir: String,
    val contacts: Int,
    val messages: Int,
)

@Serializable
data class BackupSummary(
    val path: String = "",
    val bytes: Long = 0,
    val contacts: Int = 0,
    val messages: Int = 0,
    val attachments: Int = 0,
)

@Serializable
data class ChatLink(val messageId: String, val url: String, val ts: Long)

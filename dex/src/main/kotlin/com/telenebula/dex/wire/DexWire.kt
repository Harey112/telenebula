package com.telenebula.dex.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json

/**
 * What the browser and the phone say to each other over the Dex socket. One JSON object per text
 * frame, the `t` field naming it; the web module mirrors these classes field for field.
 */
val DexJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    classDiscriminator = "t"
    classDiscriminatorMode = ClassDiscriminatorMode.POLYMORPHIC
}

@Serializable
data class DexIdentity(val name: String, val ip: String)

@Serializable
enum class DexDirection {
    @SerialName("in") IN,
    @SerialName("out") OUT,
}

@Serializable
enum class DexMessageStatus {
    @SerialName("pending") PENDING,
    @SerialName("sent") SENT,
    @SerialName("delivered") DELIVERED,
    @SerialName("offered") OFFERED,
    @SerialName("declined") DECLINED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("receiving") RECEIVING,
    @SerialName("received") RECEIVED,
}

@Serializable
enum class DexMessageKind {
    @SerialName("text") TEXT,
    @SerialName("image") IMAGE,
    @SerialName("video") VIDEO,
    @SerialName("file") FILE,
    @SerialName("voice") VOICE,
}

/** What the outbox is doing with an outgoing message; absent once it was delivered. */
@Serializable
enum class DexSendState {
    @SerialName("queued") QUEUED,
    @SerialName("sending") SENDING,
    @SerialName("waiting") WAITING,
    @SerialName("failed") FAILED,
}

@Serializable
enum class DexRevealGate {
    @SerialName("tap") TAP,
    @SerialName("ask") ASK,
    @SerialName("code") CODE,
    @SerialName("device") DEVICE,
}

@Serializable
enum class DexPresence {
    @SerialName("online") ONLINE,
    @SerialName("reachable") REACHABLE,
    @SerialName("offline") OFFLINE,
}

@Serializable
data class DexAttachment(
    val name: String,
    val mime: String,
    val size: Long,
    val width: Long? = null,
    val height: Long? = null,
    val durationMs: Long? = null,
    /** the bytes are on the phone; `GET /a/{messageId}` streams them */
    val hasFile: Boolean,
)

@Serializable
data class DexReplyPreview(val id: String, val name: String, val snippet: String)

@Serializable
data class DexMessage(
    val id: String,
    val peer: String,
    val dir: DexDirection,
    val body: String,
    val ts: Long,
    val status: DexMessageStatus,
    val kind: DexMessageKind,
    val att: DexAttachment? = null,
    val isEdited: Boolean = false,
    val isDeleted: Boolean = false,
    /** reactor ip → emoji */
    val reactions: Map<String, String> = emptyMap(),
    val replyTo: DexReplyPreview? = null,
    val seenAt: Long? = null,
    val isRead: Boolean = true,
    val expiresAt: Long? = null,
    val isCovered: Boolean = false,
    /** an incoming transfer this phone cancelled, so the browser blames nobody else */
    val isCancelledByMe: Boolean = false,
    val send: DexSendState? = null,
    /** the action a retry or cancel targets, while one is outstanding */
    val actionId: String? = null,
    /** 0..100 while a transfer runs */
    val transferPct: Int? = null,
)

@Serializable
data class DexContact(
    val ip: String,
    /** what the phone shows: nickname, else name, else ip */
    val label: String,
    val name: String,
    val nickname: String = "",
    val isBlocked: Boolean = false,
    val isArchived: Boolean = false,
    val lastSeenAt: Long? = null,
    val revealGate: DexRevealGate = DexRevealGate.TAP,
    val disappearSeconds: Int = 0,
)

@Serializable
data class DexChat(
    val peer: String,
    val label: String,
    val lastBody: String? = null,
    val lastTs: Long? = null,
    val lastDir: DexDirection? = null,
    val lastStatus: DexMessageStatus? = null,
    val lastSend: DexSendState? = null,
    val unread: Int = 0,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isBlocked: Boolean = false,
    val isMuted: Boolean = false,
    val isMarkedUnread: Boolean = false,
)

@Serializable
data class DexChatView(
    val peer: String,
    val contact: DexContact,
    /** oldest first */
    val messages: List<DexMessage>,
    val hasMore: Boolean,
    /** the phone's free storage; an offer larger than it cannot be accepted */
    val freeBytes: Long = 0,
)

@Serializable
data class DexQueue(val peer: String, val queued: Int, val isReachable: Boolean, val isDraining: Boolean)

// --- calls -----------------------------------------------------------------------------------

@Serializable
enum class DexCallPhase {
    @SerialName("idle") IDLE,
    @SerialName("incoming") INCOMING,
    @SerialName("contacting") CONTACTING,
    @SerialName("ringing") RINGING,
    @SerialName("connecting") CONNECTING,
    @SerialName("active") ACTIVE,
    @SerialName("ended") ENDED,
}

/** Where the call's media is. */
@Serializable
enum class DexSeat {
    @SerialName("none") NONE,
    @SerialName("phone") PHONE,
    @SerialName("dex") DEX,
}

@Serializable
data class DexCallPeer(val ip: String, val name: String)

/**
 * The whole call as every browser sees it, sent on each transition. A browser whose id is
 * [seatClientId] owns the media; every other one shows a banner.
 */
@Serializable
data class DexCallState(
    val phase: DexCallPhase = DexCallPhase.IDLE,
    val callId: String? = null,
    val peer: DexCallPeer? = null,
    val video: Boolean = false,
    val seat: DexSeat = DexSeat.NONE,
    val seatClientId: String? = null,
    /** epoch ms the call went active, 0 before */
    val startedAt: Long = 0,
    val remoteCamOn: Boolean = false,
    /** a move is in flight; the target is named so the seat can show it */
    val movingTo: DexSeat? = null,
    /** why an ended call ended, when it is worth reading */
    val endedReason: String? = null,
)

@Serializable
data class DexIceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

@Serializable
data class DexIceCandidate(val candidate: String, val sdpMid: String? = null, val sdpMLineIndex: Int? = null)

// --- frames from the browser -----------------------------------------------------------------

@Serializable
sealed interface ClientFrame {
    @Serializable @SerialName("ping") data object Ping : ClientFrame

    @Serializable @SerialName("open_chat") data class OpenChat(val peer: String) : ClientFrame
    @Serializable @SerialName("close_chat") data class CloseChat(val peer: String) : ClientFrame
    @Serializable @SerialName("load_more") data class LoadMore(val peer: String, val beforeTs: Long, val beforeId: String) : ClientFrame
    @Serializable @SerialName("send_text") data class SendText(val peer: String, val body: String, val replyTo: String? = null, val covered: Boolean = false) : ClientFrame
    @Serializable @SerialName("typing") data class Typing(val peer: String, val isTyping: Boolean) : ClientFrame
    @Serializable @SerialName("mark_read") data class MarkRead(val peer: String) : ClientFrame
    @Serializable @SerialName("react") data class React(val messageId: String, val emoji: String) : ClientFrame
    @Serializable @SerialName("edit") data class Edit(val messageId: String, val body: String) : ClientFrame
    @Serializable @SerialName("delete") data class Delete(val messageId: String, val forEveryone: Boolean) : ClientFrame
    @Serializable @SerialName("retry_action") data class RetryAction(val actionId: String) : ClientFrame
    @Serializable @SerialName("cancel_action") data class CancelAction(val actionId: String) : ClientFrame
    @Serializable @SerialName("accept_offer") data class AcceptOffer(val messageId: String) : ClientFrame
    @Serializable @SerialName("decline_offer") data class DeclineOffer(val messageId: String) : ClientFrame
    @Serializable @SerialName("cancel_transfer") data class CancelTransfer(val messageId: String) : ClientFrame

    @Serializable @SerialName("call_start") data class CallStart(val peer: String, val video: Boolean) : ClientFrame
    @Serializable @SerialName("call_accept") data class CallAccept(val callId: String) : ClientFrame
    @Serializable @SerialName("call_reject") data class CallReject(val callId: String) : ClientFrame
    @Serializable @SerialName("call_end") data class CallEnd(val callId: String) : ClientFrame
    /** an offer or answer the browser's peer connection produced */
    @Serializable @SerialName("call_sdp") data class CallSdp(val callId: String, val sdp: String, val sdpType: String) : ClientFrame
    /** a null candidate is end-of-candidates */
    @Serializable @SerialName("call_ice") data class CallIce(val callId: String, val candidate: DexIceCandidate? = null) : ClientFrame
    @Serializable @SerialName("call_connected") data class CallConnected(val callId: String) : ClientFrame
    @Serializable @SerialName("call_failed") data class CallFailed(val callId: String, val reason: String) : ClientFrame
    @Serializable @SerialName("call_cam") data class CallCam(val callId: String, val isOn: Boolean) : ClientFrame
    @Serializable @SerialName("call_move_to_phone") data class CallMoveToPhone(val callId: String) : ClientFrame
}

// --- frames from the phone -------------------------------------------------------------------

@Serializable
enum class DexNoticeLevel {
    @SerialName("info") INFO,
    @SerialName("warning") WARNING,
    @SerialName("error") ERROR,
}

@Serializable
sealed interface ServerFrame {
    @Serializable @SerialName("pong") data object Pong : ServerFrame

    @Serializable @SerialName("hello") data class Hello(val me: DexIdentity, val clientId: String, val freeBytes: Long) : ServerFrame
    @Serializable @SerialName("chats") data class Chats(val items: List<DexChat>) : ServerFrame
    @Serializable @SerialName("contacts") data class Contacts(val items: List<DexContact>) : ServerFrame
    @Serializable @SerialName("chat") data class Chat(val view: DexChatView) : ServerFrame
    /** older messages the browser asked for, oldest first */
    @Serializable @SerialName("chat_more") data class ChatMore(val peer: String, val messages: List<DexMessage>, val hasMore: Boolean) : ServerFrame
    @Serializable @SerialName("presence") data class Presence(val items: Map<String, DexPresence>) : ServerFrame
    @Serializable @SerialName("typing") data class Typing(val peers: List<String>) : ServerFrame
    @Serializable @SerialName("queues") data class Queues(val items: List<DexQueue>) : ServerFrame
    @Serializable @SerialName("notice") data class Notice(val level: DexNoticeLevel, val message: String) : ServerFrame
    /** a command that could not be carried out; [ref] names the frame or id it was about */
    @Serializable @SerialName("error") data class Error(val message: String, val ref: String? = null) : ServerFrame

    @Serializable @SerialName("call_state") data class CallState(val state: DexCallState) : ServerFrame
    /**
     * This browser owns the media now: open a peer connection with [iceServers]. An answerer gets
     * the remote offer in [remoteSdp]; an offerer sends its offer back as `call_sdp`. [isRestart]
     * reuses the existing connection with an ICE restart.
     */
    @Serializable @SerialName("call_media") data class CallMedia(
        val callId: String,
        val role: String,
        val video: Boolean,
        val iceServers: List<DexIceServer>,
        val remoteSdp: String? = null,
        val remoteSdpType: String? = null,
        val isRestart: Boolean = false,
    ) : ServerFrame
    @Serializable @SerialName("call_sdp") data class CallSdp(val callId: String, val sdp: String, val sdpType: String) : ServerFrame
    @Serializable @SerialName("call_ice") data class CallIce(val callId: String, val candidate: DexIceCandidate? = null) : ServerFrame
    /** this browser's peer connection is no longer part of the call */
    @Serializable @SerialName("call_release") data class CallRelease(val callId: String, val reason: String) : ServerFrame

    companion object {
        const val ROLE_OFFERER = "offerer"
        const val ROLE_ANSWERER = "answerer"
    }
}

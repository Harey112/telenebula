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
    val notes: String = "",
    val isBlocked: Boolean = false,
    val isArchived: Boolean = false,
    val isPinned: Boolean = false,
    val muteUntil: Long = 0,
    val addedAt: Long = 0,
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


// --- settings, as one document ---------------------------------------------------------------

@Serializable
enum class DexThemeMode {
    @SerialName("system") SYSTEM,
    @SerialName("light") LIGHT,
    @SerialName("dark") DARK,
}

@Serializable
enum class DexTextSize {
    @SerialName("small") SMALL,
    @SerialName("medium") MEDIUM,
    @SerialName("large") LARGE,
}

@Serializable
enum class DexDensity {
    @SerialName("comfortable") COMFORTABLE,
    @SerialName("compact") COMPACT,
}

@Serializable
enum class DexLogLevel {
    @SerialName("info") INFO,
    @SerialName("debug") DEBUG,
}

@Serializable
data class DexQuietHours(
    val enabled: Boolean = false,
    val fromHour: Int = 22,
    val fromMinute: Int = 0,
    val toHour: Int = 7,
    val toMinute: Int = 0,
)

@Serializable
data class DexMessageNotifications(
    val enabled: Boolean = true,
    val showSender: Boolean = true,
    val preview: Boolean = true,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val popup: Boolean = true,
    val reactions: Boolean = true,
)

@Serializable
data class DexCallNotifications(val ring: Boolean = true, val vibrate: Boolean = true, val missedNotification: Boolean = true)

@Serializable
data class DexInAppNotifications(val vibrate: Boolean = false)

@Serializable
data class DexNotifications(
    val messages: DexMessageNotifications = DexMessageNotifications(),
    val calls: DexCallNotifications = DexCallNotifications(),
    val inApp: DexInAppNotifications = DexInAppNotifications(),
    val quietHours: DexQuietHours = DexQuietHours(),
)

@Serializable
data class DexPresencePrefs(val isShared: Boolean = true, val pauseMinutes: Int = 0, val pausedUntil: Long = 0)

@Serializable
data class DexUpdatePrefs(val isDailyCheckEnabled: Boolean = true, val lastCheckedAt: Long = 0, val latestVersion: String? = null)

/** What the browser sets for itself; a null field follows the app's own value. */
@Serializable
data class DexSurface(
    val themeMode: DexThemeMode? = null,
    val colorTheme: String? = null,
    val customAccent: String? = null,
    val chatTextSize: DexTextSize? = null,
    val messageDensity: DexDensity? = null,
    val isEnterToSend: Boolean? = null,
    val notificationsEnabled: Boolean? = null,
    val notificationPreview: Boolean? = null,
    val notificationSound: Boolean? = null,
)

/** Everything the phone's settings screens edit. What only a phone can carry out is not here. */
@Serializable
data class DexSettings(
    val themeMode: DexThemeMode = DexThemeMode.SYSTEM,
    val colorTheme: String = "sky",
    val customAccent: String = "#7FB7E6",
    val chatTextSize: DexTextSize = DexTextSize.MEDIUM,
    val messageDensity: DexDensity = DexDensity.COMFORTABLE,
    val isEnterToSend: Boolean = false,
    val isVideoSpeakerDefault: Boolean = true,
    val isScreenshotBlocked: Boolean = false,
    val isBackgroundConnectionEnabled: Boolean = true,
    val isStartOnBootEnabled: Boolean = true,
    val notifications: DexNotifications = DexNotifications(),
    val sendReadReceipts: Boolean = true,
    val sendTypingIndicators: Boolean = true,
    val presence: DexPresencePrefs = DexPresencePrefs(),
    val updates: DexUpdatePrefs = DexUpdatePrefs(),
    val nebulaLogLevel: DexLogLevel = DexLogLevel.INFO,
    val isDeveloperMode: Boolean = false,
    val coverRevealGate: DexRevealGate = DexRevealGate.TAP,
    val autoCleanOrphans: Boolean = false,
    val quickReactions: List<String> = emptyList(),
    val recentReactions: List<String> = emptyList(),
    /** the phone's own lock, shown so the browser can say why it cannot set it */
    val isAppLockEnabled: Boolean = false,
    val appLockAfterSec: Int = 60,
    /** Dex's own settings: shown, never edited from a browser that Dex is serving */
    val dexUsername: String = "",
    val dexMaxClients: Int = 2,
    val dexPort: Int = 0,
    /** the same settings again, as the browser has them set for itself */
    val dexSurface: DexSurface = DexSurface(),
)

/** A field left out stays as it is, so a browser sends only what it changed. */
@Serializable
data class DexSettingsPatch(
    val themeMode: DexThemeMode? = null,
    val colorTheme: String? = null,
    val customAccent: String? = null,
    val chatTextSize: DexTextSize? = null,
    val messageDensity: DexDensity? = null,
    val isEnterToSend: Boolean? = null,
    val isVideoSpeakerDefault: Boolean? = null,
    val isScreenshotBlocked: Boolean? = null,
    val isBackgroundConnectionEnabled: Boolean? = null,
    val isStartOnBootEnabled: Boolean? = null,
    val notifications: DexNotifications? = null,
    val sendReadReceipts: Boolean? = null,
    val sendTypingIndicators: Boolean? = null,
    val presence: DexPresencePrefs? = null,
    val isDailyUpdateCheckEnabled: Boolean? = null,
    val nebulaLogLevel: DexLogLevel? = null,
    val isDeveloperMode: Boolean? = null,
    val coverRevealGate: DexRevealGate? = null,
    val autoCleanOrphans: Boolean? = null,
    val appLockAfterSec: Int? = null,
    val quickReactions: List<String>? = null,
    /** replaces the browser's own settings whole, so clearing one back to "follow the app" is expressible */
    val dexSurface: DexSurface? = null,
)

// --- what the phone reports about itself -----------------------------------------------------

@Serializable
data class DexAccount(
    val certName: String = "",
    val overlayIp: String = "",
    val certFingerprint: String = "",
    val certNotAfter: String = "",
    val certStatus: String = "",
    val networks: List<String> = emptyList(),
    val listenPort: Int = 0,
    val msgPort: Int = 0,
    val mtu: Int = 0,
    val lighthouseIp: String = "",
    val lighthouseUnderlay: String = "",
    val appVersion: String = "",
    val coreVersion: String = "",
)

@Serializable
data class DexPeerRow(
    val ip: String,
    val label: String,
    val isConnected: Boolean = false,
    val endpoint: String = "",
    val latencyMs: Long? = null,
)

@Serializable
data class DexNetwork(
    val isTunnelOn: Boolean = false,
    val tunnelUptimeMs: Long = 0,
    val engineUptimeMs: Long = 0,
    val connectedCount: Int = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val pendingActions: Int = 0,
    val failedActions: Int = 0,
    val pendingHandshakes: Int = 0,
    val lighthouseStatus: String = "",
    val peers: List<DexPeerRow> = emptyList(),
)

@Serializable
data class DexStorage(
    val dbBytes: Long = 0,
    val attachmentsBytes: Long = 0,
    val attachmentsCount: Int = 0,
    val orphanBytes: Long = 0,
    val orphanCount: Int = 0,
    val partialBytes: Long = 0,
    val partialCount: Int = 0,
    val messages: Int = 0,
    val contacts: Int = 0,
    val freeBytes: Long = 0,
)

@Serializable
data class DexDiagnostics(
    val logTail: String = "",
    val callTrail: List<String> = emptyList(),
    val queuedCount: Int = 0,
    val queuedPeers: Int = 0,
    val failedCount: Int = 0,
    val lighthouseStatus: String = "",
)

@Serializable
data class DexUpdates(
    val appVersion: String = "",
    val latestVersion: String? = null,
    val isUpdateAvailable: Boolean = false,
    val isDailyCheckEnabled: Boolean = true,
    val lastCheckedAt: Long = 0,
    val lastError: String? = null,
)

@Serializable
data class DexPeerStats(
    val messagesSent: Int = 0,
    val messagesReceived: Int = 0,
    val mediaSent: Int = 0,
    val mediaReceived: Int = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val firstMessageAt: Long? = null,
    val lastActivityAt: Long? = null,
    val pendingActions: Int = 0,
    val failedActions: Int = 0,
    val isConnected: Boolean = false,
)

@Serializable
enum class DexCallOutcome {
    @SerialName("answered") ANSWERED,
    @SerialName("missed") MISSED,
    @SerialName("declined") DECLINED,
    @SerialName("no-answer") NO_ANSWER,
    @SerialName("unreachable") UNREACHABLE,
    @SerialName("cancelled") CANCELLED,
    @SerialName("failed") FAILED,
}

@Serializable
data class DexCallLog(
    val id: String,
    val peer: String,
    val label: String,
    val dir: DexDirection,
    val isVideo: Boolean,
    val outcome: DexCallOutcome,
    val startedAt: Long,
    val connectedAt: Long? = null,
    val endedAt: Long,
)

@Serializable
data class DexChatLink(val messageId: String, val url: String, val ts: Long)

@Serializable
data class DexContactNotifications(
    val useGlobal: Boolean = true,
    val messages: Boolean = true,
    val preview: Boolean = true,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val popup: Boolean = true,
    val reactions: Boolean = true,
    val calls: Boolean = true,
)

/** A null field follows the global setting. */
@Serializable
data class DexContactPrivacy(
    val sendReadReceipts: Boolean? = null,
    val sendTypingIndicators: Boolean? = null,
    val blockScreenshots: Boolean? = null,
    val revealGate: DexRevealGate? = null,
)

@Serializable
data class DexContactFlags(
    val isPinned: Boolean? = null,
    val isArchived: Boolean? = null,
    val isBlocked: Boolean? = null,
    val muteUntil: Long? = null,
    val isMarkedUnread: Boolean? = null,
    val disappearSeconds: Int? = null,
)

@Serializable
data class DexContactDetail(
    val contact: DexContact,
    val stats: DexPeerStats = DexPeerStats(),
    val presence: DexPresence = DexPresence.OFFLINE,
    val clientVersion: String = "",
    val peerCertName: String = "",
    val peerCertFingerprint: String = "",
    val endpoint: String = "",
    val connectionStatus: String = "",
    val queued: Int = 0,
    val failed: Int = 0,
    val privacy: DexContactPrivacy = DexContactPrivacy(),
    val notifications: DexContactNotifications? = null,
    val calls: List<DexCallLog> = emptyList(),
)

@Serializable
data class DexPingResult(val peer: String, val rttMs: Long, val error: String? = null)

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

    @Serializable @SerialName("watch") data class Watch(val sections: List<String>) : ClientFrame
    @Serializable @SerialName("set_settings") data class SetSettings(val patch: DexSettingsPatch) : ClientFrame
    @Serializable @SerialName("set_quick_reaction") data class SetQuickReaction(val slot: Int, val emoji: String) : ClientFrame
    @Serializable @SerialName("request_contact_detail") data class RequestContactDetail(val peer: String) : ClientFrame
    @Serializable @SerialName("contact_save") data class ContactSave(val peer: String, val name: String, val nickname: String, val notes: String) : ClientFrame
    @Serializable @SerialName("contact_add") data class ContactAdd(val peer: String, val name: String, val nickname: String = "", val notes: String = "") : ClientFrame
    @Serializable @SerialName("contact_delete") data class ContactDelete(val peer: String) : ClientFrame
    @Serializable @SerialName("contact_flags") data class ContactFlagsSet(val peer: String, val flags: DexContactFlags) : ClientFrame
    @Serializable @SerialName("contact_privacy") data class ContactPrivacySet(val peer: String, val privacy: DexContactPrivacy) : ClientFrame
    @Serializable @SerialName("contact_notifications") data class ContactNotificationsSet(val peer: String, val prefs: DexContactNotifications? = null) : ClientFrame
    @Serializable @SerialName("contact_change_ip") data class ContactChangeIp(val peer: String, val newIp: String) : ClientFrame
    @Serializable @SerialName("clear_history") data class ClearHistory(val peer: String) : ClientFrame
    @Serializable @SerialName("clear_all_history") data object ClearAllHistory : ClientFrame
    @Serializable @SerialName("clear_orphans") data object ClearOrphans : ClientFrame
    @Serializable @SerialName("request_call_logs") data class RequestCallLogs(val peer: String? = null, val limit: Int = 200) : ClientFrame
    @Serializable @SerialName("delete_call_logs") data class DeleteCallLogs(val ids: List<String>) : ClientFrame
    @Serializable @SerialName("request_chat_media") data class RequestChatMedia(val peer: String) : ClientFrame
    @Serializable @SerialName("request_chat_links") data class RequestChatLinks(val peer: String) : ClientFrame
    @Serializable @SerialName("ping_peer") data class PingPeer(val peer: String) : ClientFrame
    @Serializable @SerialName("retry_failed") data class RetryFailed(val peer: String = "") : ClientFrame
    @Serializable @SerialName("drain") data class Drain(val peer: String) : ClientFrame
    @Serializable @SerialName("check_updates") data object CheckUpdates : ClientFrame
    @Serializable @SerialName("set_tunnel") data class SetTunnel(val isOn: Boolean) : ClientFrame
    @Serializable @SerialName("forward") data class Forward(val messageId: String, val peer: String) : ClientFrame
    @Serializable @SerialName("search") data class Search(val peer: String, val text: String) : ClientFrame
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

    @Serializable @SerialName("settings") data class Settings(val settings: DexSettings) : ServerFrame
    @Serializable @SerialName("account") data class Account(val account: DexAccount) : ServerFrame
    @Serializable @SerialName("network") data class Network(val network: DexNetwork) : ServerFrame
    @Serializable @SerialName("storage") data class Storage(val storage: DexStorage) : ServerFrame
    @Serializable @SerialName("diagnostics") data class Diagnostics(val diagnostics: DexDiagnostics) : ServerFrame
    @Serializable @SerialName("updates") data class Updates(val updates: DexUpdates) : ServerFrame
    @Serializable @SerialName("call_logs") data class CallLogs(val items: List<DexCallLog>) : ServerFrame
    @Serializable @SerialName("contact_detail") data class ContactDetail(val detail: DexContactDetail) : ServerFrame
    @Serializable @SerialName("chat_media") data class ChatMedia(val peer: String, val items: List<DexMessage>) : ServerFrame
    @Serializable @SerialName("chat_links") data class ChatLinks(val peer: String, val items: List<DexChatLink>) : ServerFrame
    @Serializable @SerialName("ping_result") data class PingResult(val result: DexPingResult) : ServerFrame
    @Serializable @SerialName("search_results") data class SearchResults(val peer: String, val items: List<DexMessage>) : ServerFrame
    /** a command that finished and has nothing to send back but the fact */
    @Serializable @SerialName("done") data class Done(val what: String, val message: String? = null) : ServerFrame

    companion object {
        const val ROLE_OFFERER = "offerer"
        const val ROLE_ANSWERER = "answerer"
    }
}

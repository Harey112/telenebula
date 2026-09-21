package com.telenebula.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode {
    @SerialName("system") SYSTEM,
    @SerialName("light") LIGHT,
    @SerialName("dark") DARK,
}

@Serializable
enum class ChatTextSize {
    @SerialName("small") SMALL,
    @SerialName("medium") MEDIUM,
    @SerialName("large") LARGE,
}

@Serializable
enum class MessageDensity {
    @SerialName("comfortable") COMFORTABLE,
    @SerialName("compact") COMPACT,
}

@Serializable
data class MessageNotificationPrefs(
    val enabled: Boolean = true,
    /** show who wrote, or a generic "TeleNebula" title */
    val showSender: Boolean = true,
    /** show the text, or just "New message" */
    val preview: Boolean = true,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    /** heads-up (high importance) vs a quiet shade entry */
    val popup: Boolean = true,
    val reactions: Boolean = true,
)

@Serializable
data class CallNotificationPrefs(
    /** ring for incoming calls (off = auto-decline) */
    val ring: Boolean = true,
    val vibrate: Boolean = true,
    val missedNotification: Boolean = true,
)

@Serializable
data class InAppNotificationPrefs(val vibrate: Boolean = false)

@Serializable
data class QuietHours(
    val enabled: Boolean = false,
    val fromHour: Int = 22,
    val fromMinute: Int = 0,
    val toHour: Int = 7,
    val toMinute: Int = 0,
) {
    val fromMinuteOfDay: Int get() = fromHour * 60 + fromMinute
    val toMinuteOfDay: Int get() = toHour * 60 + toMinute
}

/** Only ever quieter than the system: ringer mode and Do Not Disturb still apply on top. */
@Serializable
data class NotificationPrefs(
    val messages: MessageNotificationPrefs = MessageNotificationPrefs(),
    val calls: CallNotificationPrefs = CallNotificationPrefs(),
    val inApp: InAppNotificationPrefs = InAppNotificationPrefs(),
    val quietHours: QuietHours = QuietHours(),
)

/** Per-contact overrides stored on the contact; `useGlobal` true = inherit everything. */
@Serializable
data class ContactNotificationPrefs(
    val useGlobal: Boolean = true,
    val messages: Boolean = true,
    val preview: Boolean = true,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val popup: Boolean = true,
    val reactions: Boolean = true,
    /** allow this contact to ring you (independent of useGlobal) */
    val calls: Boolean = true,
)

/** Whether peers may see "online" rather than only "reachable"; a pause ends at [pausedUntil]. */
@Serializable
data class PresencePrefs(
    val isShared: Boolean = true,
    /** the pause length chosen, for the settings screen; 0 = none */
    val pauseMinutes: Int = 0,
    /** epoch ms; 0 = not paused */
    val pausedUntil: Long = 0,
) {
    fun isSharingAt(nowMs: Long): Boolean = isShared && (pausedUntil == 0L || nowMs >= pausedUntil)

    /** Whoever hides their own "online" sees nobody else's: a peer shows as reachable at most. */
    fun seenAs(peer: PeerPresence?, nowMs: Long): PeerPresence? =
        if (peer == PeerPresence.ONLINE && !isSharingAt(nowMs)) PeerPresence.REACHABLE else peer
}

/** Automatic release checks and what the last one found; [latestVersion] drives the badges. */
@Serializable
data class UpdatePrefs(
    val isDailyCheckEnabled: Boolean = true,
    /** epoch ms of the last check that answered; 0 = never */
    val lastCheckedAt: Long = 0,
    val latestVersion: String? = null,
    /** the version the notification was shown for, so each release is announced once */
    val notifiedVersion: String? = null,
)

/** The web frontend served by the phone; the password is stored as a salted PBKDF2 hash, never in clear. */
@Serializable
data class DexPrefs(
    val isEnabled: Boolean = false,
    val username: String = "",
    val passwordAlgorithm: String = "",
    val passwordIterations: Int = 0,
    val passwordSalt: String = "",
    val passwordHash: String = "",
    /** browsers logged in at once */
    val maxClients: Int = 2,
    val port: Int = 8420,
    val turnPort: Int = 8421,
) {
    val hasPassword: Boolean get() = passwordHash.isNotEmpty() && passwordSalt.isNotEmpty() && passwordIterations > 0
    val hasCredentials: Boolean get() = username.isNotBlank() && hasPassword
}

/** Defaults equal the previous builds' DEFAULT_PREFS; decoding a partial file merges over them. */
@Serializable
data class Prefs(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** key of a built-in colour theme, or "custom" for the accent below */
    val colorTheme: String = "sky",
    val customAccent: String = "#7FB7E6",
    val chatTextSize: ChatTextSize = ChatTextSize.MEDIUM,
    val isVideoSpeakerDefault: Boolean = true,
    val isScreenshotBlocked: Boolean = false,
    val isBackgroundConnectionEnabled: Boolean = true,
    /** the system starts the app at boot and after an update, so the tunnel comes up on its own */
    val isStartOnBootEnabled: Boolean = true,
    val notifications: NotificationPrefs = NotificationPrefs(),
    val sendReadReceipts: Boolean = true,
    val sendTypingIndicators: Boolean = true,
    val presence: PresencePrefs = PresencePrefs(),
    val updates: UpdatePrefs = UpdatePrefs(),
    val messageDensity: MessageDensity = MessageDensity.COMFORTABLE,
    val isEnterToSend: Boolean = false,
    val nebulaLogLevel: NebulaLogLevel = NebulaLogLevel.INFO,
    val isDeveloperMode: Boolean = false,
    val isAppLockEnabled: Boolean = false,
    val coverRevealGate: CoverRevealGate = CoverRevealGate.TAP,
    /** seconds in the background before the lock re-arms (0 = immediately) */
    val appLockAfterSec: Int = 60,
    val autoCleanOrphans: Boolean = false,
    /** most recently used reactions, newest first (never one of quickReactions) */
    val recentReactions: List<String> = emptyList(),
    /** the six reactions offered first */
    val quickReactions: List<String> = DEFAULT_QUICK_REACTIONS,
    val dex: DexPrefs = DexPrefs(),
) {
    companion object {
        val DEFAULT_QUICK_REACTIONS: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
        const val MAX_RECENT_REACTIONS = 21
        /** muteUntil sentinel shared with the core */
        const val MUTE_FOREVER = -1L
    }
}

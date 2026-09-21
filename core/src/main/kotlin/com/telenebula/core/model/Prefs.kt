package com.telenebula.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    /** show who wrote, or a generic "TeleNebula" title */
    val showSender: Boolean = true,
    val vibrate: Boolean = true,
    /** heads-up (high importance) vs a quiet shade entry */
    val popup: Boolean = true,
    val reactions: Boolean = true,
    /** owned by whichever profile is asking, never written here; filled in when the core is told */
    @Transient val enabled: Boolean = true,
    /** show the text, or just "New message" */
    @Transient val preview: Boolean = true,
    @Transient val sound: Boolean = true,
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

/**
 * What one profile sets for itself, a profile being the app or Dex. A null field follows the app,
 * so a phone that never opens Dex behaves as before and an older prefs file needs no migration.
 *
 * Only a setting that describes a screen belongs here. Everything else is a core setting: it
 * governs the account, the protocol, the peers or the device, it is the same in every profile,
 * and it must never gain a per-profile value. Core settings are, deliberately and exhaustively:
 * read receipts, typing indicators, presence, the cover reveal gate, screenshot blocking, the app
 * lock and its delay, the background connection, start on boot, the nebula log level, developer
 * mode, orphan cleaning, the daily update check, the quick reactions, and everything under `dex`.
 */
/** The same in every profile: what a peer, the tunnel, the outbox or the device itself can observe. */
@Serializable
data class CorePrefs(
    val presence: PresencePrefs = PresencePrefs(),
    val isScreenshotBlocked: Boolean = false,
    val isAppLockEnabled: Boolean = false,
    /** seconds in the background before the lock re-arms (0 = immediately) */
    val appLockAfterSec: Int = 60,
    val isBackgroundConnectionEnabled: Boolean = true,
    /** the system starts the app at boot and after an update, so the tunnel comes up on its own */
    val isStartOnBootEnabled: Boolean = true,
    val nebulaLogLevel: NebulaLogLevel = NebulaLogLevel.INFO,
    val isDeveloperMode: Boolean = false,
    val autoCleanOrphans: Boolean = false,
    val updates: UpdatePrefs = UpdatePrefs(),
    /** the six reactions offered first */
    val quickReactions: List<String> = Prefs.DEFAULT_QUICK_REACTIONS,
    /** most recently used reactions, newest first (never one of quickReactions) */
    val recentReactions: List<String> = emptyList(),
    val notifications: NotificationPrefs = NotificationPrefs(),
)

/** What one profile shows and how it behaves; the app always has a value for each. */
@Serializable
data class AppProfile(
    val sendReadReceipts: Boolean = true,
    val sendTypingIndicators: Boolean = true,
    /** the app may use the device's own lock; Dex never can */
    val coverRevealGate: CoverRevealGate = CoverRevealGate.TAP,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** key of a built-in colour theme, or "custom" for the accent below */
    val colorTheme: String = "sky",
    val customAccent: String = "#7FB7E6",
    val chatTextSize: ChatTextSize = ChatTextSize.MEDIUM,
    val messageDensity: MessageDensity = MessageDensity.COMFORTABLE,
    val isEnterToSend: Boolean = false,
    val isVideoSpeakerDefault: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val notificationPreview: Boolean = true,
    val notificationSound: Boolean = true,
)

/**
 * The same settings as the app profile, as Dex has them for itself; a null field follows the app.
 *
 * Only a setting that describes a screen belongs here. Everything a peer, the tunnel or the device
 * can observe is a core setting, lives in [CorePrefs], and is the same in every profile.
 */
@Serializable
data class DexProfile(
    // privacy is the browser's own and never follows the app: what it tells a peer, and what it
    // takes to open a covered message here, are not decided by a setting changed on the phone
    val sendReadReceipts: Boolean = true,
    val sendTypingIndicators: Boolean = true,
    /** never DEVICE: a browser cannot answer the phone's lock */
    val coverRevealGate: CoverRevealGate = CoverRevealGate.TAP,
    val themeMode: ThemeMode? = null,
    val colorTheme: String? = null,
    val customAccent: String? = null,
    val chatTextSize: ChatTextSize? = null,
    val messageDensity: MessageDensity? = null,
    val isEnterToSend: Boolean? = null,
    val notificationsEnabled: Boolean? = null,
    val notificationPreview: Boolean? = null,
    val notificationSound: Boolean? = null,
)


/** Dex the service, not Dex the profile; the password is stored as a salted PBKDF2 hash, never in clear. */
@Serializable
data class DexServer(
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
    /**
     * 1 was one flat object; 2 groups it, so the next move is a case in a when rather than a risk.
     * Always written: the encoder omits defaults, and a file with no version is read as version 1.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val version: Int = CURRENT_VERSION,
    val core: CorePrefs = CorePrefs(),
    val app: AppProfile = AppProfile(),
    val dex: DexProfile = DexProfile(),
    val server: DexServer = DexServer(),
) {
    companion object {
        const val CURRENT_VERSION = 2
        val DEFAULT_QUICK_REACTIONS: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
        const val MAX_RECENT_REACTIONS = 21
        /** muteUntil sentinel shared with the core */
        const val MUTE_FOREVER = -1L
    }
}

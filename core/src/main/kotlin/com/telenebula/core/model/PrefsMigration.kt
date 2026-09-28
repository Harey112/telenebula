package com.telenebula.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Version 1 was one flat object. Every shipped build wrote it, so a file that predates the split
 * has to be read in its own shape and moved across; decoding it as version 2 would silently hand
 * the user back the defaults for everything they had ever set.
 */
@Serializable
private data class PrefsV1(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorTheme: String = "sky",
    val customAccent: String = "#7FB7E6",
    val chatTextSize: ChatTextSize = ChatTextSize.MEDIUM,
    val isVideoSpeakerDefault: Boolean = true,
    val isScreenshotBlocked: Boolean = false,
    val isBackgroundConnectionEnabled: Boolean = true,
    val isStartOnBootEnabled: Boolean = true,
    val notifications: NotificationPrefsV1 = NotificationPrefsV1(),
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
    val appLockAfterSec: Int = 60,
    val autoCleanOrphans: Boolean = false,
    val recentReactions: List<String> = emptyList(),
    val quickReactions: List<String> = Prefs.DEFAULT_QUICK_REACTIONS,
    val dex: DexServer = DexServer(),
)

@Serializable
private data class NotificationPrefsV1(
    val messages: MessageNotificationPrefsV1 = MessageNotificationPrefsV1(),
    val calls: CallNotificationPrefs = CallNotificationPrefs(),
    val inApp: InAppNotificationPrefs = InAppNotificationPrefs(),
    val quietHours: QuietHours = QuietHours(),
)

/** The three a profile now owns still lived here in version 1. */
@Serializable
private data class MessageNotificationPrefsV1(
    val enabled: Boolean = true,
    val showSender: Boolean = true,
    val preview: Boolean = true,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val popup: Boolean = true,
    val reactions: Boolean = true,
)

/** Version 2 as it was written: Dex's presentation was null wherever it followed the app. */
@Serializable
private data class PrefsV2(
    val core: CorePrefs = CorePrefs(),
    val app: AppProfile = AppProfile(),
    val dex: DexProfileV2 = DexProfileV2(),
    val server: DexServer = DexServer(),
)

@Serializable
private data class DexProfileV2(
    val sendReadReceipts: Boolean = true,
    val sendTypingIndicators: Boolean = true,
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

object PrefsMigration {
    /** Reads whichever version the text holds; a file with no version is the flat one. */
    fun decode(json: Json, text: String): Prefs {
        val version = runCatching {
            (json.parseToJsonElement(text) as? JsonObject)?.get("version")?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: 1
        return when {
            version >= Prefs.CURRENT_VERSION -> json.decodeFromString(Prefs.serializer(), text)
            version == 2 -> json.decodeFromString(PrefsV2.serializer(), text).toV3()
            else -> json.decodeFromString(PrefsV1.serializer(), text).toV2().toV3()
        }
    }

    /** Whatever Dex followed is pinned to what the phone had then, so the browser looks the same after the upgrade. */
    private fun PrefsV2.toV3(): Prefs = Prefs(
        version = Prefs.CURRENT_VERSION,
        core = core,
        app = app,
        dex = DexProfile(
            sendReadReceipts = dex.sendReadReceipts,
            sendTypingIndicators = dex.sendTypingIndicators,
            coverRevealGate = if (dex.coverRevealGate == CoverRevealGate.DEVICE) CoverRevealGate.ASK else dex.coverRevealGate,
            themeMode = dex.themeMode ?: app.themeMode,
            colorTheme = dex.colorTheme ?: app.colorTheme,
            customAccent = dex.customAccent ?: app.customAccent,
            chatTextSize = dex.chatTextSize ?: app.chatTextSize,
            messageDensity = dex.messageDensity ?: app.messageDensity,
            isEnterToSend = dex.isEnterToSend ?: app.isEnterToSend,
            notificationsEnabled = dex.notificationsEnabled ?: app.notificationsEnabled,
            notificationPreview = dex.notificationPreview ?: app.notificationPreview,
            notificationSound = dex.notificationSound ?: app.notificationSound,
        ),
        server = server,
    )

    private fun PrefsV1.toV2(): PrefsV2 = PrefsV2(
        core = CorePrefs(
            presence = presence,
            isScreenshotBlocked = isScreenshotBlocked,
            isAppLockEnabled = isAppLockEnabled,
            appLockAfterSec = appLockAfterSec,
            isBackgroundConnectionEnabled = isBackgroundConnectionEnabled,
            isStartOnBootEnabled = isStartOnBootEnabled,
            nebulaLogLevel = nebulaLogLevel,
            isDeveloperMode = isDeveloperMode,
            autoCleanOrphans = autoCleanOrphans,
            updates = updates,
            quickReactions = quickReactions,
            recentReactions = recentReactions,
            notifications = NotificationPrefs(
                messages = MessageNotificationPrefs(
                    showSender = notifications.messages.showSender,
                    vibrate = notifications.messages.vibrate,
                    popup = notifications.messages.popup,
                    reactions = notifications.messages.reactions,
                ),
                calls = notifications.calls,
                inApp = notifications.inApp,
                quietHours = notifications.quietHours,
            ),
        ),
        app = AppProfile(
            sendReadReceipts = sendReadReceipts,
            sendTypingIndicators = sendTypingIndicators,
            coverRevealGate = coverRevealGate,
            themeMode = themeMode,
            colorTheme = colorTheme,
            customAccent = customAccent,
            chatTextSize = chatTextSize,
            messageDensity = messageDensity,
            isEnterToSend = isEnterToSend,
            isVideoSpeakerDefault = isVideoSpeakerDefault,
            notificationsEnabled = notifications.messages.enabled,
            notificationPreview = notifications.messages.preview,
            notificationSound = notifications.messages.sound,
        ),
        // version 1 had no second profile: what it shows follows the app, but its privacy is its
        // own from here on, so it starts at what the phone was set to rather than at the defaults
        dex = DexProfileV2(
            sendReadReceipts = sendReadReceipts,
            sendTypingIndicators = sendTypingIndicators,
            coverRevealGate = if (coverRevealGate == CoverRevealGate.DEVICE) CoverRevealGate.ASK else coverRevealGate,
        ),
        server = dex,
    )
}

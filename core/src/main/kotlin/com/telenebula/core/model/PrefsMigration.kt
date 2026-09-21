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

object PrefsMigration {
    /** Reads whichever version the text holds; a file with no version is the flat one. */
    fun decode(json: Json, text: String): Prefs {
        val version = runCatching {
            (json.parseToJsonElement(text) as? JsonObject)?.get("version")?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: 1
        if (version >= Prefs.CURRENT_VERSION) return json.decodeFromString(Prefs.serializer(), text)
        return json.decodeFromString(PrefsV1.serializer(), text).toV2()
    }

    private fun PrefsV1.toV2(): Prefs = Prefs(
        version = Prefs.CURRENT_VERSION,
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
        // version 1 had no second profile, so Dex starts out following the app
        dex = DexProfile(),
        server = dex,
    )
}

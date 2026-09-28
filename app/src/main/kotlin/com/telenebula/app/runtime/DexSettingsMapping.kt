package com.telenebula.app.runtime

import com.telenebula.core.model.NebulaLogLevel
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.PresencePrefs
import com.telenebula.dex.wire.DexCallNotifications
import com.telenebula.dex.wire.DexInAppNotifications
import com.telenebula.dex.wire.DexLogLevel
import com.telenebula.dex.wire.DexMessageNotifications
import com.telenebula.dex.wire.DexNotifications
import com.telenebula.dex.wire.DexPresencePrefs
import com.telenebula.dex.wire.DexQuietHours
import com.telenebula.dex.wire.DexSettings
import com.telenebula.dex.wire.DexSettingsPatch
import com.telenebula.dex.wire.DexUpdatePrefs

/** What a browser is shown of the prefs and what its patch may change: the shared settings and Dex's own, never the app's. */
object DexSettingsMapping {
    fun settings(p: Prefs): DexSettings = DexSettings(
        isScreenshotBlocked = p.core.isScreenshotBlocked,
        isBackgroundConnectionEnabled = p.core.isBackgroundConnectionEnabled,
        isStartOnBootEnabled = p.core.isStartOnBootEnabled,
        notifications = DexNotifications(
            messages = p.core.notifications.messages.let { DexMessageNotifications(it.showSender, it.vibrate, it.popup, it.reactions) },
            calls = p.core.notifications.calls.let { DexCallNotifications(it.ring, it.vibrate, it.missedNotification) },
            inApp = DexInAppNotifications(p.core.notifications.inApp.vibrate),
            quietHours = p.core.notifications.quietHours.let { DexQuietHours(it.enabled, it.fromHour, it.fromMinute, it.toHour, it.toMinute) },
        ),
        presence = DexPresencePrefs(p.core.presence.isShared, p.core.presence.pauseMinutes, p.core.presence.pausedUntil),
        updates = DexUpdatePrefs(p.core.updates.isDailyCheckEnabled, p.core.updates.lastCheckedAt, p.core.updates.latestVersion),
        nebulaLogLevel = if (p.core.nebulaLogLevel == NebulaLogLevel.DEBUG) DexLogLevel.DEBUG else DexLogLevel.INFO,
        isDeveloperMode = p.core.isDeveloperMode,
        autoCleanOrphans = p.core.autoCleanOrphans,
        quickReactions = p.core.quickReactions,
        recentReactions = p.core.recentReactions,
        isAppLockEnabled = p.core.isAppLockEnabled,
        appLockAfterSec = p.core.appLockAfterSec,
        dexUsername = p.server.username,
        dexMaxClients = p.server.maxClients,
        dexPort = p.server.port,
        dexProfile = DexProfiles.toWire(p.dex),
    )

    /** Only the fields the wire carries can change: presence, the log level and the browser's own profile. */
    fun apply(p: Prefs, patch: DexSettingsPatch): Prefs = p.copy(
        core = p.core.copy(
            presence = patch.presence?.let { PresencePrefs(it.isShared, it.pauseMinutes.coerceAtLeast(0), it.pausedUntil.coerceAtLeast(0)) } ?: p.core.presence,
            nebulaLogLevel = patch.nebulaLogLevel?.toCore() ?: p.core.nebulaLogLevel,
        ),
        dex = patch.dexProfile?.let { DexProfiles.toCore(it, p.dex) } ?: p.dex,
    )

    fun isEmoji(value: String): Boolean = value.isNotEmpty() && value.length <= MAX_EMOJI_CHARS

    private fun DexLogLevel.toCore(): NebulaLogLevel = if (this == DexLogLevel.DEBUG) NebulaLogLevel.DEBUG else NebulaLogLevel.INFO

    private const val MAX_EMOJI_CHARS = 16
}

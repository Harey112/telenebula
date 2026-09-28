package com.telenebula.app.runtime

import com.telenebula.core.model.AppProfile
import com.telenebula.core.model.ChatTextSize
import com.telenebula.core.model.CorePrefs
import com.telenebula.core.model.CoverRevealGate
import com.telenebula.core.model.DexServer
import com.telenebula.core.model.MessageDensity
import com.telenebula.core.model.MessageNotificationPrefs
import com.telenebula.core.model.NotificationPrefs
import com.telenebula.core.model.QuietHours
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.ThemeMode
import com.telenebula.dex.wire.DexDensity
import com.telenebula.dex.wire.DexJson
import com.telenebula.dex.wire.DexLogLevel
import com.telenebula.dex.wire.DexPresencePrefs
import com.telenebula.dex.wire.DexProfile
import com.telenebula.dex.wire.DexRevealGate
import com.telenebula.dex.wire.DexSettingsPatch
import com.telenebula.dex.wire.DexTextSize
import com.telenebula.dex.wire.DexThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexSettingsMappingTest {
    private val phone = AppProfile(
        sendReadReceipts = false,
        sendTypingIndicators = false,
        coverRevealGate = CoverRevealGate.DEVICE,
        themeMode = ThemeMode.DARK,
        colorTheme = "coral",
        customAccent = "#ABCDEF",
        chatTextSize = ChatTextSize.SMALL,
        messageDensity = MessageDensity.COMPACT,
        isEnterToSend = true,
        isVideoSpeakerDefault = false,
        notificationsEnabled = false,
        notificationPreview = false,
        notificationSound = false,
    )

    private val server = DexServer(
        isEnabled = true,
        username = "harey",
        passwordAlgorithm = "PBKDF2WithHmacSHA256",
        passwordIterations = 120_000,
        passwordSalt = "c2FsdA==",
        passwordHash = "aGFzaA==",
        maxClients = 4,
        port = 9443,
        turnPort = 9444,
    )

    @Test
    fun `a change to the phone's own profile shows the browser nothing new`() {
        val before = Prefs()
        assertEquals(DexSettingsMapping.settings(before), DexSettingsMapping.settings(before.copy(app = phone)))
    }

    @Test
    fun `no browser patch reaches the phone's own profile, its own switches or the Dex login`() {
        val core = CorePrefs(
            isScreenshotBlocked = true,
            isStartOnBootEnabled = true,
            isDeveloperMode = false,
            notifications = NotificationPrefs(quietHours = QuietHours(enabled = false)),
        )
        val before = Prefs(core = core, app = phone, server = server)
        val everything = DexSettingsPatch(
            presence = DexPresencePrefs(isShared = false),
            nebulaLogLevel = DexLogLevel.DEBUG,
            dexProfile = DexProfile(
                sendReadReceipts = true,
                sendTypingIndicators = true,
                coverRevealGate = DexRevealGate.TAP,
                themeMode = DexThemeMode.LIGHT,
                colorTheme = "sky",
                customAccent = "#000000",
                chatTextSize = DexTextSize.LARGE,
                messageDensity = DexDensity.COMFORTABLE,
                isEnterToSend = false,
                notificationsEnabled = true,
                notificationPreview = true,
                notificationSound = true,
            ),
        )
        val after = DexSettingsMapping.apply(before, everything)
        assertEquals(phone, after.app)
        assertEquals(server, after.server)
        assertEquals(ThemeMode.LIGHT, after.dex.themeMode)
        assertEquals(before.core.copy(presence = after.core.presence, nebulaLogLevel = after.core.nebulaLogLevel), after.core)
        assertFalse(after.core.presence.isShared)
    }

    @Test
    fun `a crafted frame naming the phone's own switches decodes to a patch that changes nothing`() {
        val before = Prefs(core = CorePrefs(isScreenshotBlocked = true, isStartOnBootEnabled = true, isDeveloperMode = false, appLockAfterSec = 60), app = phone, server = server)
        val crafted = """{"isScreenshotBlocked":false,"isStartOnBootEnabled":false,"isBackgroundConnectionEnabled":false,"isDeveloperMode":true,
            "appLockAfterSec":0,"autoCleanOrphans":true,"isDailyUpdateCheckEnabled":false,"quickReactions":["x","x","x","x","x","x"],
            "notifications":{"calls":{"ring":false}}}"""
        val patch = DexJson.decodeFromString(DexSettingsPatch.serializer(), crafted)
        assertEquals(before, DexSettingsMapping.apply(before, patch))
    }

    @Test
    fun `resetting the browser's notifications leaves the phone's and the shared ones as they were`() {
        val shared = CorePrefs(notifications = NotificationPrefs(messages = MessageNotificationPrefs(showSender = false), quietHours = QuietHours(enabled = true)))
        val before = Prefs(core = shared, app = phone, dex = com.telenebula.core.model.DexProfile(notificationsEnabled = false))
        val after = DexSettingsMapping.apply(before, DexSettingsPatch(dexProfile = DexProfiles.toWire(before.dex).copy(notificationsEnabled = true)))
        assertEquals(before.core, after.core)
        assertEquals(before.app, after.app)
        assertTrue(after.dex.notificationsEnabled)
    }

    @Test
    fun `an empty patch changes nothing`() {
        val before = Prefs(core = CorePrefs(isDeveloperMode = true), app = phone, server = server)
        assertEquals(before, DexSettingsMapping.apply(before, DexSettingsPatch()))
    }
}

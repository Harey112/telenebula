package com.telenebula.core

import com.telenebula.core.model.ChatTextSize
import com.telenebula.core.model.CoverRevealGate
import com.telenebula.core.model.DexProfile
import com.telenebula.core.model.MessageDensity
import com.telenebula.core.model.NebulaLogLevel
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.PrefsMigration
import com.telenebula.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version 1 shipped, so files in this shape exist on real phones. Reading one as version 2 would
 * hand the user the defaults for everything they had ever set, silently, on the next launch.
 */
class PrefsMigrationTest {
    /** The shape 1.1.3 wrote, with a value set in every group the split moved things between. */
    private val version1 = """
        {
          "themeMode": "dark",
          "colorTheme": "moss",
          "customAccent": "#AABBCC",
          "chatTextSize": "large",
          "messageDensity": "compact",
          "isEnterToSend": true,
          "isVideoSpeakerDefault": false,
          "isScreenshotBlocked": true,
          "isBackgroundConnectionEnabled": false,
          "isStartOnBootEnabled": false,
          "sendReadReceipts": false,
          "sendTypingIndicators": false,
          "coverRevealGate": "code",
          "isAppLockEnabled": true,
          "appLockAfterSec": 300,
          "nebulaLogLevel": "debug",
          "isDeveloperMode": true,
          "autoCleanOrphans": true,
          "quickReactions": ["a", "b", "c", "d", "e", "f"],
          "recentReactions": ["z"],
          "presence": { "isShared": false, "pauseMinutes": 30, "pausedUntil": 99 },
          "updates": { "isDailyCheckEnabled": false, "lastCheckedAt": 1234, "latestVersion": "1.1.3" },
          "notifications": {
            "messages": { "enabled": false, "showSender": false, "preview": false, "sound": false, "vibrate": false, "popup": false, "reactions": false },
            "calls": { "ring": false, "vibrate": false, "missedNotification": false },
            "inApp": { "vibrate": true },
            "quietHours": { "enabled": true, "fromHour": 1, "fromMinute": 2, "toHour": 3, "toMinute": 4 }
          },
          "dex": { "isEnabled": true, "username": "harey", "maxClients": 5, "port": 9000, "turnPort": 9001 }
        }
    """.trimIndent()

    @Test
    fun `a version 1 file keeps every value it had`() {
        val p = PrefsMigration.decode(CoreJson, version1)
        assertEquals(Prefs.CURRENT_VERSION, p.version)

        assertEquals(ThemeMode.DARK, p.app.themeMode)
        assertEquals("moss", p.app.colorTheme)
        assertEquals("#AABBCC", p.app.customAccent)
        assertEquals(ChatTextSize.LARGE, p.app.chatTextSize)
        assertEquals(MessageDensity.COMPACT, p.app.messageDensity)
        assertTrue(p.app.isEnterToSend)
        assertFalse(p.app.isVideoSpeakerDefault)
        assertFalse(p.app.sendReadReceipts)
        assertFalse(p.app.sendTypingIndicators)
        assertEquals(CoverRevealGate.CODE, p.app.coverRevealGate)

        // the three the split moved out of notifications.messages and into the profile
        assertFalse(p.app.notificationsEnabled)
        assertFalse(p.app.notificationPreview)
        assertFalse(p.app.notificationSound)

        assertTrue(p.core.isScreenshotBlocked)
        assertFalse(p.core.isBackgroundConnectionEnabled)
        assertFalse(p.core.isStartOnBootEnabled)
        assertTrue(p.core.isAppLockEnabled)
        assertEquals(300, p.core.appLockAfterSec)
        assertEquals(NebulaLogLevel.DEBUG, p.core.nebulaLogLevel)
        assertTrue(p.core.isDeveloperMode)
        assertTrue(p.core.autoCleanOrphans)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), p.core.quickReactions)
        assertEquals(listOf("z"), p.core.recentReactions)
        assertFalse(p.core.presence.isShared)
        assertEquals(30, p.core.presence.pauseMinutes)
        assertFalse(p.core.updates.isDailyCheckEnabled)
        assertEquals("1.1.3", p.core.updates.latestVersion)

        assertFalse(p.core.notifications.messages.showSender)
        assertFalse(p.core.notifications.messages.vibrate)
        assertFalse(p.core.notifications.messages.popup)
        assertFalse(p.core.notifications.messages.reactions)
        assertFalse(p.core.notifications.calls.ring)
        assertTrue(p.core.notifications.inApp.vibrate)
        assertTrue(p.core.notifications.quietHours.enabled)
        assertEquals(1, p.core.notifications.quietHours.fromHour)
        assertEquals(4, p.core.notifications.quietHours.toMinute)

        assertTrue(p.server.isEnabled)
        assertEquals("harey", p.server.username)
        assertEquals(5, p.server.maxClients)
        assertEquals(9000, p.server.port)

        // version 1 had no second profile: Dex starts at everything the phone was set to, then diverges
        assertEquals(
            DexProfile(
                sendReadReceipts = false,
                sendTypingIndicators = false,
                coverRevealGate = CoverRevealGate.CODE,
                themeMode = ThemeMode.DARK,
                colorTheme = "moss",
                customAccent = "#AABBCC",
                chatTextSize = ChatTextSize.LARGE,
                messageDensity = MessageDensity.COMPACT,
                isEnterToSend = true,
                notificationsEnabled = false,
                notificationPreview = false,
                notificationSound = false,
            ),
            p.dex,
        )
    }

    /** The real file on a test phone: sparse, because only what differs from a default is written. */
    @Test
    fun `a sparse version 1 file takes the defaults for everything it omits`() {
        val p = PrefsMigration.decode(
            CoreJson,
            """{"updates":{"lastCheckedAt":1789982412632,"latestVersion":"1.1.3"},"coverRevealGate":"code","dex":{"isEnabled":true,"username":"harey"}}""",
        )
        assertEquals(CoverRevealGate.CODE, p.app.coverRevealGate)
        assertEquals("harey", p.server.username)
        assertTrue(p.server.isEnabled)
        assertEquals(ThemeMode.SYSTEM, p.app.themeMode)
        assertTrue(p.app.sendReadReceipts)
        assertEquals(CoverRevealGate.CODE, p.dex.coverRevealGate)
        assertEquals(Prefs.DEFAULT_QUICK_REACTIONS, p.core.quickReactions)
    }

    /** What a version 2 build wrote: Dex set some things itself and followed the phone for the rest. */
    private val version2 = """
        {
          "version": 2,
          "core": { "isDeveloperMode": true, "notifications": { "messages": { "showSender": false }, "quietHours": { "enabled": true } } },
          "app": {
            "sendReadReceipts": false,
            "coverRevealGate": "device",
            "themeMode": "dark",
            "colorTheme": "coral",
            "chatTextSize": "large",
            "isEnterToSend": true,
            "isVideoSpeakerDefault": false,
            "notificationsEnabled": false,
            "notificationSound": false
          },
          "dex": { "sendTypingIndicators": false, "coverRevealGate": "ask", "themeMode": "light", "messageDensity": "compact", "notificationSound": true },
          "server": {
            "isEnabled": true, "username": "harey", "passwordAlgorithm": "PBKDF2WithHmacSHA256", "passwordIterations": 120000,
            "passwordSalt": "c2FsdA==", "passwordHash": "aGFzaA==", "maxClients": 4, "port": 9443, "turnPort": 9444
          }
        }
    """.trimIndent()

    @Test
    fun `a version 2 file pins what Dex followed to the phone's value and keeps what Dex set itself`() {
        val p = PrefsMigration.decode(CoreJson, version2)
        assertEquals(Prefs.CURRENT_VERSION, p.version)
        assertEquals(
            DexProfile(
                // privacy was already Dex's own in version 2, so the phone's receipts do not cross
                sendReadReceipts = true,
                sendTypingIndicators = false,
                coverRevealGate = CoverRevealGate.ASK,
                themeMode = ThemeMode.LIGHT,
                colorTheme = "coral",
                customAccent = "#7FB7E6",
                chatTextSize = ChatTextSize.LARGE,
                messageDensity = MessageDensity.COMPACT,
                isEnterToSend = true,
                notificationsEnabled = false,
                notificationPreview = true,
                notificationSound = true,
            ),
            p.dex,
        )
        assertFalse(p.app.sendReadReceipts)
        assertEquals(CoverRevealGate.DEVICE, p.app.coverRevealGate)
        assertEquals(ThemeMode.DARK, p.app.themeMode)
        assertEquals(MessageDensity.COMFORTABLE, p.app.messageDensity)
        assertFalse(p.app.isVideoSpeakerDefault)
        assertFalse(p.app.notificationSound)
        assertTrue(p.core.isDeveloperMode)
        assertFalse(p.core.notifications.messages.showSender)
        assertTrue(p.core.notifications.quietHours.enabled)
    }

    @Test
    fun `a version 2 file keeps the Dex login and limits`() {
        val s = PrefsMigration.decode(CoreJson, version2).server
        assertTrue(s.isEnabled)
        assertTrue(s.hasCredentials)
        assertEquals("harey", s.username)
        assertEquals("PBKDF2WithHmacSHA256", s.passwordAlgorithm)
        assertEquals(120000, s.passwordIterations)
        assertEquals("c2FsdA==", s.passwordSalt)
        assertEquals("aGFzaA==", s.passwordHash)
        assertEquals(4, s.maxClients)
        assertEquals(9443, s.port)
        assertEquals(9444, s.turnPort)
    }

    /** A version 2 phone that never opened Dex has no dex group at all; the browser still looks as it did. */
    @Test
    fun `a version 2 file with no Dex profile gives Dex the phone's look and the old privacy defaults`() {
        val p = PrefsMigration.decode(CoreJson, """{"version":2,"app":{"themeMode":"dark","coverRevealGate":"code","sendReadReceipts":false}}""")
        assertEquals(ThemeMode.DARK, p.dex.themeMode)
        assertEquals(CoverRevealGate.TAP, p.dex.coverRevealGate)
        assertTrue(p.dex.sendReadReceipts)
    }

    @Test
    fun `a version 2 Dex profile holding the phone's lock is left asking instead`() {
        val p = PrefsMigration.decode(CoreJson, """{"version":2,"dex":{"coverRevealGate":"device"}}""")
        assertEquals(CoverRevealGate.ASK, p.dex.coverRevealGate)
    }

    @Test
    fun `a current file is read as it is, not migrated again`() {
        val once = PrefsMigration.decode(CoreJson, version2)
        val text = CoreJson.encodeToString(Prefs.serializer(), once)
        assertTrue(text.contains("\"version\":${Prefs.CURRENT_VERSION}"))
        assertEquals(once, PrefsMigration.decode(CoreJson, text))
    }

    @Test
    fun `a migrated version 1 file is not migrated again`() {
        val once = PrefsMigration.decode(CoreJson, version1)
        val text = CoreJson.encodeToString(Prefs.serializer(), once)
        assertEquals(once, PrefsMigration.decode(CoreJson, text))
    }

    /** The phone's lock is the one gate a browser can never answer, so it must not be handed one. */
    @Test
    fun `a phone using its own lock leaves Dex asking instead`() {
        val p = PrefsMigration.decode(CoreJson, """{"coverRevealGate":"device"}""")
        assertEquals(CoverRevealGate.DEVICE, p.app.coverRevealGate)
        assertEquals(CoverRevealGate.ASK, p.dex.coverRevealGate)
    }

    @Test
    fun `an empty or broken file still yields usable defaults`() {
        assertEquals(Prefs(), PrefsMigration.decode(CoreJson, "{}"))
    }
}

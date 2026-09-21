package com.telenebula.core

import com.telenebula.core.model.ChatTextSize
import com.telenebula.core.model.CoverRevealGate
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

        // version 1 had no second profile, so Dex starts out following the app
        assertEquals(Prefs().dex, p.dex)
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
        assertEquals(Prefs.DEFAULT_QUICK_REACTIONS, p.core.quickReactions)
    }

    @Test
    fun `a version 2 file is read as it is, not migrated again`() {
        val once = PrefsMigration.decode(CoreJson, version1)
        val text = CoreJson.encodeToString(Prefs.serializer(), once)
        assertEquals(once, PrefsMigration.decode(CoreJson, text))
    }

    @Test
    fun `an empty or broken file still yields usable defaults`() {
        assertEquals(Prefs(), PrefsMigration.decode(CoreJson, "{}"))
    }
}

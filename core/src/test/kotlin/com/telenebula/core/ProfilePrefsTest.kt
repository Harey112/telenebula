package com.telenebula.core

import com.telenebula.core.model.AppProfile
import com.telenebula.core.model.CorePrefs
import com.telenebula.core.model.DexProfile
import com.telenebula.core.model.MessageNotificationPrefs
import com.telenebula.core.model.NotificationPrefs
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.QuietHours
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A profile is the app or Dex. Only a setting that describes a screen may differ between them;
 * a core setting governs the account, the protocol, the peers or the device and is the same
 * everywhere. This is the guard on that, since the cost of getting it wrong is a peer seeing one
 * thing while the phone believes another.
 */
@OptIn(ExperimentalSerializationApi::class)
class ProfilePrefsTest {
    private fun names(descriptor: SerialDescriptor): Set<String> =
        (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()

    private val profileSettings = setOf(
        "sendReadReceipts",
        "sendTypingIndicators",
        "coverRevealGate",
        "themeMode",
        "colorTheme",
        "customAccent",
        "chatTextSize",
        "messageDensity",
        "isEnterToSend",
        "notificationsEnabled",
        "notificationPreview",
        "notificationSound",
    )

    /** Everything a peer, the tunnel, the outbox or the device's own security can observe. */
    private val coreSettings = setOf(
        "presence",
        "isScreenshotBlocked",
        "isAppLockEnabled",
        "appLockAfterSec",
        "isBackgroundConnectionEnabled",
        "isStartOnBootEnabled",
        "nebulaLogLevel",
        "isDeveloperMode",
        "autoCleanOrphans",
        "updates",
        "quickReactions",
        "recentReactions",
        "dex",
    )

    /** A nullable Dex setting would follow the app, so a change made on the phone would reach the browser. */
    @Test
    fun `no setting in the Dex profile follows the app`() {
        val descriptor = DexProfile.serializer().descriptor
        for (i in 0 until descriptor.elementsCount) {
            assertFalse(descriptor.getElementName(i), descriptor.getElementDescriptor(i).isNullable)
        }
    }

    @Test
    fun `the Dex profile has every setting the app profile has, bar the phone's loudspeaker`() {
        assertEquals(names(AppProfile.serializer().descriptor) - "isVideoSpeakerDefault", names(DexProfile.serializer().descriptor))
    }

    @Test
    fun `the phone's notifications take the app's three and the shared rest, never Dex's`() {
        val p = Prefs(
            core = CorePrefs(notifications = NotificationPrefs(messages = MessageNotificationPrefs(showSender = false, vibrate = false), quietHours = QuietHours(enabled = true))),
            app = AppProfile(notificationsEnabled = false, notificationPreview = false, notificationSound = true),
            dex = DexProfile(notificationsEnabled = true, notificationPreview = true, notificationSound = false),
        )
        val n = p.appNotifications()
        assertFalse(n.messages.enabled)
        assertFalse(n.messages.preview)
        assertTrue(n.messages.sound)
        assertFalse(n.messages.showSender)
        assertFalse(n.messages.vibrate)
        assertTrue(n.quietHours.enabled)
    }

    @Test
    fun `a profile carries only the settings that describe a screen`() {
        assertEquals(profileSettings, names(DexProfile.serializer().descriptor))
    }

    @Test
    fun `no core setting can be given a different value per profile`() {
        val perProfile = names(DexProfile.serializer().descriptor)
        for (setting in coreSettings) {
            assertTrue("$setting is a core setting and must be the same in every profile", setting !in perProfile)
        }
    }

    @Test
    fun `every core setting still exists on the prefs it belongs to`() {
        val all = names(CorePrefs.serializer().descriptor) + names(Prefs.serializer().descriptor)
        for (setting in coreSettings) {
            assertTrue("$setting was renamed or removed; decide whether it is still a core setting", setting in all)
        }
    }
}

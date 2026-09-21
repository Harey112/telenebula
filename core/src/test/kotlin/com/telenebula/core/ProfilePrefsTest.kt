package com.telenebula.core

import com.telenebula.core.model.CorePrefs
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.DexProfile
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import org.junit.Assert.assertEquals
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

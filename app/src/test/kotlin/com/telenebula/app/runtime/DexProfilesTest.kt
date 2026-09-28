package com.telenebula.app.runtime

import app.cash.turbine.test
import com.telenebula.core.CoreJson
import com.telenebula.core.model.AppProfile
import com.telenebula.core.model.ChatTextSize
import com.telenebula.core.model.CoverRevealGate
import com.telenebula.core.model.MessageDensity
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.ThemeMode
import com.telenebula.dex.wire.DexRevealGate
import com.telenebula.dex.wire.DexSettingsPatch
import com.telenebula.dex.wire.DexThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.telenebula.core.model.DexProfile as DexProfilePrefs

class DexProfilesTest {
    private val everyFieldSet = DexProfilePrefs(
        sendReadReceipts = false,
        sendTypingIndicators = false,
        coverRevealGate = CoverRevealGate.CODE,
        themeMode = ThemeMode.DARK,
        colorTheme = "violet",
        customAccent = "#123456",
        chatTextSize = ChatTextSize.LARGE,
        messageDensity = MessageDensity.COMPACT,
        isEnterToSend = true,
        notificationsEnabled = false,
        notificationPreview = false,
        notificationSound = false,
    )

    @Test
    fun `the sample sets every Dex setting away from its default`() {
        val written = CoreJson.encodeToJsonElement(DexProfilePrefs.serializer(), everyFieldSet).jsonObject.keys
        val all = DexProfilePrefs.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() }
        assertEquals(all, written)
    }

    @Test
    fun `every Dex setting crosses the wire and comes back`() {
        assertEquals(everyFieldSet, DexProfiles.toCore(DexProfiles.toWire(everyFieldSet), DexProfilePrefs()))
    }

    /** The browser edits one setting by sending its whole profile back, as it was shown. */
    @Test
    fun `changing one Dex setting from the browser keeps every other one`() {
        val before = Prefs(dex = everyFieldSet)
        val shown = DexSettingsMapping.settings(before).dexProfile
        val after = DexSettingsMapping.apply(before, DexSettingsPatch(dexProfile = shown.copy(themeMode = DexThemeMode.LIGHT)))
        assertEquals(everyFieldSet.copy(themeMode = ThemeMode.LIGHT), after.dex)
    }

    @Test
    fun `a colour the phone would not store keeps the one it had`() {
        val wire = DexProfiles.toWire(everyFieldSet).copy(customAccent = "red", colorTheme = " ")
        val back = DexProfiles.toCore(wire, everyFieldSet)
        assertEquals("#123456", back.customAccent)
        assertEquals("violet", back.colorTheme)
    }

    @Test
    fun `a browser cannot be handed the phone's lock`() {
        val wire = DexProfiles.toWire(everyFieldSet).copy(coverRevealGate = DexRevealGate.DEVICE)
        assertEquals(CoverRevealGate.ASK, DexProfiles.toCore(wire, everyFieldSet).coverRevealGate)
    }

    @Test
    fun `a chat's own gate wins over Dex's, and the phone's lock stays the phone's`() {
        assertEquals(DexRevealGate.CODE, DexProfiles.gate(null, CoverRevealGate.CODE))
        assertEquals(DexRevealGate.TAP, DexProfiles.gate(CoverRevealGate.TAP, CoverRevealGate.CODE))
        assertEquals(DexRevealGate.CODE, DexProfiles.gate(CoverRevealGate.CODE, CoverRevealGate.TAP))
        assertEquals(DexRevealGate.DEVICE, DexProfiles.gate(CoverRevealGate.DEVICE, CoverRevealGate.TAP))
    }

    @Test
    fun `only a gate a browser can answer lets it fetch a covered attachment`() {
        assertTrue(DexProfiles.canRevealInBrowser(DexProfiles.gate(null, CoverRevealGate.TAP)))
        assertTrue(DexProfiles.canRevealInBrowser(DexProfiles.gate(CoverRevealGate.ASK, CoverRevealGate.CODE)))
        assertFalse(DexProfiles.canRevealInBrowser(DexProfiles.gate(CoverRevealGate.DEVICE, CoverRevealGate.TAP)))
        assertFalse(DexProfiles.canRevealInBrowser(DexProfiles.gate(CoverRevealGate.CODE, CoverRevealGate.TAP)))
        assertFalse(DexProfiles.canRevealInBrowser(DexProfiles.gate(null, CoverRevealGate.CODE)))
    }

    @Test
    fun `a browser may tighten a chat's gate but never loosen one the phone locked`() {
        assertTrue(DexProfiles.isOverrideChangeAllowed(null, CoverRevealGate.CODE))
        assertTrue(DexProfiles.isOverrideChangeAllowed(CoverRevealGate.TAP, CoverRevealGate.ASK))
        assertTrue(DexProfiles.isOverrideChangeAllowed(CoverRevealGate.ASK, null))
        assertTrue(DexProfiles.isOverrideChangeAllowed(CoverRevealGate.CODE, CoverRevealGate.DEVICE))
        assertTrue(DexProfiles.isOverrideChangeAllowed(CoverRevealGate.CODE, CoverRevealGate.CODE))
        assertFalse(DexProfiles.isOverrideChangeAllowed(CoverRevealGate.CODE, CoverRevealGate.TAP))
        assertFalse(DexProfiles.isOverrideChangeAllowed(CoverRevealGate.DEVICE, CoverRevealGate.ASK))
        // following the global gate could loosen it later from the browser's own settings
        assertFalse(DexProfiles.isOverrideChangeAllowed(CoverRevealGate.CODE, null))
        assertFalse(DexProfiles.isOverrideChangeAllowed(CoverRevealGate.DEVICE, null))
    }

    @Test
    fun `browsers hear of a Dex gate change at once, and never of the phone's`() = runTest {
        val prefs = MutableStateFlow(Prefs())
        DexProfiles.dexGate(prefs).test {
            assertEquals(CoverRevealGate.TAP, awaitItem())
            prefs.update { it.copy(app = AppProfile(coverRevealGate = CoverRevealGate.DEVICE)) }
            expectNoEvents()
            prefs.update { it.copy(dex = it.dex.copy(coverRevealGate = CoverRevealGate.CODE)) }
            assertEquals(CoverRevealGate.CODE, awaitItem())
        }
    }
}

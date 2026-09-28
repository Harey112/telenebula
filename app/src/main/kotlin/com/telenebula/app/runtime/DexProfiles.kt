package com.telenebula.app.runtime

import com.telenebula.core.model.ChatTextSize
import com.telenebula.core.model.CoverRevealGate
import com.telenebula.core.model.MessageDensity
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.ThemeMode
import com.telenebula.dex.wire.DexDensity
import com.telenebula.dex.wire.DexProfile
import com.telenebula.dex.wire.DexRevealGate
import com.telenebula.dex.wire.DexTextSize
import com.telenebula.dex.wire.DexThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.telenebula.core.model.DexProfile as DexProfilePrefs

/** The browser's own profile across the wire, and the one rule for opening a covered message there. */
object DexProfiles {
    fun toWire(p: DexProfilePrefs): DexProfile = DexProfile(
        sendReadReceipts = p.sendReadReceipts,
        sendTypingIndicators = p.sendTypingIndicators,
        coverRevealGate = p.coverRevealGate.toWire(),
        themeMode = p.themeMode.toWire(),
        colorTheme = p.colorTheme,
        customAccent = p.customAccent,
        chatTextSize = p.chatTextSize.toWire(),
        messageDensity = p.messageDensity.toWire(),
        isEnterToSend = p.isEnterToSend,
        notificationsEnabled = p.notificationsEnabled,
        notificationPreview = p.notificationPreview,
        notificationSound = p.notificationSound,
    )

    /** A value the phone would not store keeps what [current] had, so one bad field never resets the rest. */
    fun toCore(w: DexProfile, current: DexProfilePrefs): DexProfilePrefs = DexProfilePrefs(
        sendReadReceipts = w.sendReadReceipts,
        sendTypingIndicators = w.sendTypingIndicators,
        coverRevealGate = w.coverRevealGate.toCore().let { if (it == CoverRevealGate.DEVICE) CoverRevealGate.ASK else it },
        themeMode = w.themeMode.toCore(),
        colorTheme = w.colorTheme.trim().takeIf { it.isNotEmpty() && it.length <= MAX_THEME_CHARS } ?: current.colorTheme,
        customAccent = w.customAccent.takeIf { ACCENT.matches(it) } ?: current.customAccent,
        chatTextSize = w.chatTextSize.toCore(),
        messageDensity = w.messageDensity.toCore(),
        isEnterToSend = w.isEnterToSend,
        notificationsEnabled = w.notificationsEnabled,
        notificationPreview = w.notificationPreview,
        notificationSound = w.notificationSound,
    )

    /** What a browser shows for a chat and what it may fetch: the chat's own override, else Dex's gate; the phone's lock stays the phone's. */
    fun gate(override: CoverRevealGate?, dexGate: CoverRevealGate): DexRevealGate = (override ?: dexGate).toWire()

    /** A code or the phone's lock is answered on the handset, so only a tap or a question can be answered in a browser. */
    fun canRevealInBrowser(gate: DexRevealGate): Boolean = gate == DexRevealGate.TAP || gate == DexRevealGate.ASK

    /** A chat the phone put behind its code or lock is loosened on the phone, never by the browser it keeps out. */
    fun isOverrideChangeAllowed(current: CoverRevealGate?, next: CoverRevealGate?): Boolean =
        !isPhoneOnly(current) || isPhoneOnly(next)

    private fun isPhoneOnly(gate: CoverRevealGate?): Boolean = gate == CoverRevealGate.CODE || gate == CoverRevealGate.DEVICE

    fun dexGate(prefs: Flow<Prefs>): Flow<CoverRevealGate> = prefs.map { it.dex.coverRevealGate }.distinctUntilChanged()

    const val MAX_THEME_CHARS = 32
    val ACCENT = Regex("#[0-9a-fA-F]{6}")
}

internal fun CoverRevealGate.toWire(): DexRevealGate = when (this) {
    CoverRevealGate.TAP -> DexRevealGate.TAP
    CoverRevealGate.ASK -> DexRevealGate.ASK
    CoverRevealGate.CODE -> DexRevealGate.CODE
    CoverRevealGate.DEVICE -> DexRevealGate.DEVICE
}

internal fun DexRevealGate.toCore(): CoverRevealGate = when (this) {
    DexRevealGate.TAP -> CoverRevealGate.TAP
    DexRevealGate.ASK -> CoverRevealGate.ASK
    DexRevealGate.CODE -> CoverRevealGate.CODE
    DexRevealGate.DEVICE -> CoverRevealGate.DEVICE
}

private fun ThemeMode.toWire(): DexThemeMode = when (this) {
    ThemeMode.SYSTEM -> DexThemeMode.SYSTEM
    ThemeMode.LIGHT -> DexThemeMode.LIGHT
    ThemeMode.DARK -> DexThemeMode.DARK
}

private fun DexThemeMode.toCore(): ThemeMode = when (this) {
    DexThemeMode.SYSTEM -> ThemeMode.SYSTEM
    DexThemeMode.LIGHT -> ThemeMode.LIGHT
    DexThemeMode.DARK -> ThemeMode.DARK
}

private fun ChatTextSize.toWire(): DexTextSize = when (this) {
    ChatTextSize.SMALL -> DexTextSize.SMALL
    ChatTextSize.MEDIUM -> DexTextSize.MEDIUM
    ChatTextSize.LARGE -> DexTextSize.LARGE
}

private fun DexTextSize.toCore(): ChatTextSize = when (this) {
    DexTextSize.SMALL -> ChatTextSize.SMALL
    DexTextSize.MEDIUM -> ChatTextSize.MEDIUM
    DexTextSize.LARGE -> ChatTextSize.LARGE
}

private fun MessageDensity.toWire(): DexDensity = when (this) {
    MessageDensity.COMFORTABLE -> DexDensity.COMFORTABLE
    MessageDensity.COMPACT -> DexDensity.COMPACT
}

private fun DexDensity.toCore(): MessageDensity = when (this) {
    DexDensity.COMFORTABLE -> MessageDensity.COMFORTABLE
    DexDensity.COMPACT -> MessageDensity.COMPACT
}

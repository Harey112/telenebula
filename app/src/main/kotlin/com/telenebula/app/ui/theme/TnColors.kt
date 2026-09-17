package com.telenebula.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class Appearance { LIGHT, DARK }

/** The complete token set. Nothing outside `ui/theme` names a colour any other way. */
@Immutable
data class TnColors(
    val appearance: Appearance,
    /** page ground */
    val background: Color,
    /** cards, bars, incoming bubbles in light mode */
    val surface: Color,
    /** inputs, chips, pressed rows */
    val surfaceRaised: Color,
    val hairline: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    /** tint behind accent icons and the active tab */
    val accentSoft: Color,
    /** text and icons drawn on the accent */
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val bubbleIn: Color,
    val bubbleOut: Color,
    val onBubbleOut: Color,
    /** modal and sheet backdrop */
    val overlay: Color,
) {
    val isDark: Boolean get() = appearance == Appearance.DARK
}

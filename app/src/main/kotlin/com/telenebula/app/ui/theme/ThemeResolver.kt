package com.telenebula.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.telenebula.core.model.ThemeMode

/** Pure colour maths behind the theme; ported from the RN `lib/theme.ts`. */
object ThemeResolver {
    private val hexPattern = Regex("^#[0-9a-fA-F]{6}$")

    fun isValidHex(value: String): Boolean = hexPattern.matches(value)

    fun parseHex(hex: String): Color? {
        if (!isValidHex(hex)) return null
        val rgb = hex.substring(1).toLong(16)
        return Color(0xFF000000L or rgb)
    }

    /** "#RRGGBB" upper-case, the form stored in prefs. */
    fun toHex(color: Color): String {
        val argb = color.toArgb()
        return "#%06X".format(argb and 0xFFFFFF)
    }

    fun resolveAccent(colorTheme: String, customAccent: String): Color {
        if (colorTheme == ColorThemes.CUSTOM) return parseHex(customAccent) ?: ColorThemes.defaultAccent
        return ColorThemes.options.firstOrNull { it.key == colorTheme }?.accent ?: ColorThemes.defaultAccent
    }

    fun resolveAppearance(mode: ThemeMode, isSystemDark: Boolean): Appearance = when (mode) {
        ThemeMode.SYSTEM -> if (isSystemDark) Appearance.DARK else Appearance.LIGHT
        ThemeMode.LIGHT -> Appearance.LIGHT
        ThemeMode.DARK -> Appearance.DARK
    }

    /** Builds the full token set for one appearance and accent. */
    fun buildTheme(appearance: Appearance, accent: Color): TnColors {
        val isLight = appearance == Appearance.LIGHT
        val onAccent = if (accent.luminance() > 0.5f) ColorThemes.onAccentDark else ColorThemes.onAccentLight
        return if (isLight) {
            TnColors(
                appearance = appearance,
                background = ColorThemes.Light.background,
                surface = ColorThemes.Light.surface,
                surfaceRaised = ColorThemes.Light.surfaceRaised,
                hairline = ColorThemes.Light.hairline,
                text = ColorThemes.Light.text,
                textMuted = ColorThemes.Light.textMuted,
                accent = accent,
                accentSoft = lerp(accent, ColorThemes.Light.surface, 0.82f),
                onAccent = onAccent,
                success = ColorThemes.success,
                warning = ColorThemes.warning,
                danger = ColorThemes.danger,
                info = ColorThemes.info,
                bubbleIn = ColorThemes.Light.surface,
                bubbleOut = accent,
                onBubbleOut = onAccent,
                overlay = ColorThemes.Light.overlay,
            )
        } else {
            TnColors(
                appearance = appearance,
                background = ColorThemes.Dark.background,
                surface = ColorThemes.Dark.surface,
                surfaceRaised = ColorThemes.Dark.surfaceRaised,
                hairline = ColorThemes.Dark.hairline,
                text = ColorThemes.Dark.text,
                textMuted = ColorThemes.Dark.textMuted,
                accent = accent,
                accentSoft = lerp(accent, ColorThemes.Dark.surface, 0.76f),
                onAccent = onAccent,
                success = ColorThemes.success,
                warning = ColorThemes.warning,
                danger = ColorThemes.danger,
                info = ColorThemes.info,
                bubbleIn = ColorThemes.Dark.surfaceRaised,
                bubbleOut = accent,
                onBubbleOut = onAccent,
                overlay = ColorThemes.Dark.overlay,
            )
        }
    }

    /** 18 hues in two tones — the custom palette picker's swatches. */
    fun paletteSwatches(): List<Color> {
        val out = ArrayList<Color>(36)
        for (l in floatArrayOf(0.72f, 0.56f)) {
            var h = 0f
            while (h < 360f) {
                out.add(Color.hsl(h, 0.55f, l))
                h += 20f
            }
        }
        return out
    }

    fun avatarColor(seed: String): Color {
        var h = 0
        for (ch in seed) h = h * 31 + ch.code
        val index = (h.toLong() and 0xFFFFFFFFL) % ColorThemes.avatarPalette.size
        return ColorThemes.avatarPalette[index.toInt()]
    }

    fun initialOf(name: String): String {
        val t = name.trim()
        return if (t.isEmpty()) "?" else t.substring(0, 1).uppercase()
    }
}

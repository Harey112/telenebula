package com.telenebula.app.ui.theme

import androidx.compose.ui.graphics.Color

data class ColorThemeOption(val key: String, val label: String, val accent: Color)

/** Hex values live here and nowhere else (plus the always-dark call screen and media viewer). */
object ColorThemes {
    val options: List<ColorThemeOption> = listOf(
        ColorThemeOption("sky", "Sky", Color(0xFF7FB7E6)),
        ColorThemeOption("sage", "Sage", Color(0xFF7DBB9C)),
        ColorThemeOption("lavender", "Lavender", Color(0xFFA594E4)),
        ColorThemeOption("coral", "Coral", Color(0xFFE58E82)),
        ColorThemeOption("graphite", "Graphite", Color(0xFF7E8A97)),
    )
    const val CUSTOM = "custom"
    val defaultAccent = Color(0xFF7FB7E6)

    internal object Light {
        val background = Color(0xFFF6F7F9)
        val surface = Color(0xFFFFFFFF)
        val surfaceRaised = Color(0xFFEDF0F4)
        val hairline = Color(0xFFE2E6EB)
        val text = Color(0xFF151A20)
        val textMuted = Color(0xFF6B7684)
        val overlay = Color(0x66101720)
    }

    internal object Dark {
        val background = Color(0xFF0F1216)
        val surface = Color(0xFF171B21)
        val surfaceRaised = Color(0xFF20262E)
        val hairline = Color(0xFF262D36)
        val text = Color(0xFFF2F5F8)
        val textMuted = Color(0xFF8B96A2)
        val overlay = Color(0x99000000)
    }

    internal val success = Color(0xFF3AA76D)
    internal val warning = Color(0xFFD9982E)
    internal val danger = Color(0xFFE2504F)
    internal val info = Color(0xFF1FB6D1)
    internal val onAccentDark = Color(0xFF12171D)
    internal val onAccentLight = Color(0xFFFFFFFF)

    /** Identity colours for avatars, picked by name hash (same hash as the RN app). */
    val avatarPalette: List<Color> = listOf(
        Color(0xFFD9776C), Color(0xFFE0955A), Color(0xFF8F86DE), Color(0xFF6FB86A),
        Color(0xFF5FB4C9), Color(0xFF5A93D1), Color(0xFFD97A9E),
    )
}

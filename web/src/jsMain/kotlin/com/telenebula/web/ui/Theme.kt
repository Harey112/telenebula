package com.telenebula.web.ui

import com.telenebula.web.state.Effective
import com.telenebula.web.wire.DexDensity
import com.telenebula.web.wire.DexTextSize
import com.telenebula.web.wire.DexThemeMode
import kotlinx.browser.document

/** What this browser resolved for itself, painted onto the document. */
object Theme {
    private val palettes = mapOf(
        "sky" to ("#5FA3DB" to "#7FB7E6"),
        "forest" to ("#4C9A6A" to "#6FC291"),
        "amber" to ("#C8862A" to "#E0A94E"),
        "rose" to ("#C85C7A" to "#E58AA3"),
        "violet" to ("#7A63C8" to "#A38FE5"),
        "slate" to ("#5A6B7C" to "#8AA0B4"),
    )

    fun apply(s: Effective) {
        val root = document.documentElement ?: return
        val theme = when (s.themeMode) {
            DexThemeMode.SYSTEM -> "system"
            DexThemeMode.LIGHT -> "light"
            DexThemeMode.DARK -> "dark"
        }
        root.setAttribute("data-theme", theme)
        val text = when (s.chatTextSize) {
            DexTextSize.SMALL -> "small"
            DexTextSize.MEDIUM -> "medium"
            DexTextSize.LARGE -> "large"
        }
        root.setAttribute("data-text", text)
        val density = if (s.messageDensity == DexDensity.COMPACT) "compact" else "comfortable"
        root.setAttribute("data-density", density)
        val custom = s.customAccent.takeIf { it.startsWith("#") && it.length == 7 }
        val pair = palettes[s.colorTheme]
        val light = if (s.colorTheme == "custom") custom else pair?.first
        val dark = if (s.colorTheme == "custom") custom else pair?.second
        val style = root.asDynamic().style
        if (light != null) style.setProperty("--accent-light", light)
        if (dark != null) style.setProperty("--accent-dark", dark)
    }
}

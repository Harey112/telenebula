package com.telenebula.app.ui.screens.appearance

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import com.telenebula.app.nav.Navigator
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.ui.theme.ColorThemes
import com.telenebula.app.ui.theme.ThemeResolver
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.ThemeMode
import kotlinx.coroutines.flow.StateFlow

class AppearanceViewModel(private val prefs: PrefsRepository, private val navigator: Navigator) : ViewModel() {
    val state: StateFlow<Prefs> = prefs.prefs
    val swatches: List<Color> = ThemeResolver.paletteSwatches()

    fun setMode(mode: ThemeMode) = prefs.update { it.copy(themeMode = mode) }
    fun setColorTheme(key: String) = prefs.update { it.copy(colorTheme = key) }
    fun setCustomAccent(color: Color) = prefs.update { it.copy(customAccent = ThemeResolver.toHex(color), colorTheme = ColorThemes.CUSTOM) }
    fun goBack() = navigator.pop()
}

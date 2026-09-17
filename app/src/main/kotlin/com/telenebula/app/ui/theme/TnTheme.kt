package com.telenebula.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.telenebula.core.model.ThemeMode

val LocalTnColors = staticCompositionLocalOf<TnColors> { error("TnTheme is not applied") }

/** Read tokens as `TnTheme.colors.accent`; never `MaterialTheme.colorScheme`. */
object TnTheme {
    val colors: TnColors
        @Composable @ReadOnlyComposable get() = LocalTnColors.current
}

/**
 * Root theme from prefs and the system scheme. Material 3 is applied underneath only so its
 * primitives (Switch, Slider, ripple, Text defaults) take our tokens; app code never reads it.
 */
@Composable
fun TnTheme(themeMode: ThemeMode, colorTheme: String, customAccent: String, content: @Composable () -> Unit) {
    val isSystemDark = isSystemInDarkTheme()
    val colors = remember(themeMode, colorTheme, customAccent, isSystemDark) {
        ThemeResolver.buildTheme(
            ThemeResolver.resolveAppearance(themeMode, isSystemDark),
            ThemeResolver.resolveAccent(colorTheme, customAccent),
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !colors.isDark
                    isAppearanceLightNavigationBars = !colors.isDark
                }
            }
        }
    }
    CompositionLocalProvider(
        LocalTnColors provides colors,
        LocalContentColor provides colors.text,
        LocalTextStyle provides TnType.body,
    ) {
        MaterialTheme(colorScheme = colors.toMaterialScheme(), content = content)
    }
}

private fun TnColors.toMaterialScheme() = (if (isDark) darkColorScheme() else lightColorScheme()).copy(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = accentSoft,
    onPrimaryContainer = text,
    secondary = accent,
    onSecondary = onAccent,
    background = background,
    onBackground = text,
    surface = surface,
    onSurface = text,
    surfaceVariant = surfaceRaised,
    onSurfaceVariant = textMuted,
    surfaceContainer = surface,
    surfaceContainerLow = surface,
    surfaceContainerHigh = surfaceRaised,
    surfaceContainerHighest = surfaceRaised,
    outline = hairline,
    outlineVariant = hairline,
    error = danger,
    onError = onAccentLightOr(),
    scrim = overlay,
)

private fun TnColors.onAccentLightOr() = if (isDark) text else surface

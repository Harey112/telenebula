package com.telenebula.app.platform

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import com.telenebula.core.model.ThemeMode

/** The system draws the splash and the window background before the app runs; on API 31+ it can follow the app's own mode. */
class SystemNightMode(context: Context) {
    private val uiModes: UiModeManager? = context.getSystemService(UiModeManager::class.java)

    fun apply(mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        uiModes?.setApplicationNightMode(
            when (mode) {
                ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }
}

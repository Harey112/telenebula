package com.telenebula.web.state

import com.telenebula.web.wire.DexDensity
import com.telenebula.web.wire.DexSettings
import com.telenebula.web.wire.DexTextSize
import com.telenebula.web.wire.DexThemeMode

/**
 * What this browser actually uses: its own value where it has set one, the app's where it has not.
 * Every view reads these rather than falling back on its own, so the two can never disagree.
 */
data class Effective(
    val themeMode: DexThemeMode = DexThemeMode.SYSTEM,
    val colorTheme: String = "sky",
    val customAccent: String = "#7FB7E6",
    val chatTextSize: DexTextSize = DexTextSize.MEDIUM,
    val messageDensity: DexDensity = DexDensity.COMFORTABLE,
    val isEnterToSend: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val notificationPreview: Boolean = true,
    val notificationSound: Boolean = true,
) {
    companion object {
        val NONE = Effective()

        fun of(s: DexSettings?): Effective {
            if (s == null) return NONE
            val d = s.dexSurface
            return Effective(
                themeMode = d.themeMode ?: s.themeMode,
                colorTheme = d.colorTheme ?: s.colorTheme,
                customAccent = d.customAccent ?: s.customAccent,
                chatTextSize = d.chatTextSize ?: s.chatTextSize,
                messageDensity = d.messageDensity ?: s.messageDensity,
                isEnterToSend = d.isEnterToSend ?: s.isEnterToSend,
                notificationsEnabled = d.notificationsEnabled ?: s.notifications.messages.enabled,
                notificationPreview = d.notificationPreview ?: s.notifications.messages.preview,
                notificationSound = d.notificationSound ?: s.notifications.messages.sound,
            )
        }
    }
}

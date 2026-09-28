package com.telenebula.app.ui.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Navigator
import com.telenebula.app.platform.OpenWith
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.ui.shared.OpenMenu
import com.telenebula.app.ui.shared.uiState
import com.telenebula.core.model.AppProfile
import com.telenebula.core.model.NotificationPrefs
import com.telenebula.core.model.Prefs
import com.telenebula.core.service.TnCoreService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/** [prefs] is shared with Dex; the three message switches are this phone's own. */
data class NotificationsUiState(
    val prefs: NotificationPrefs,
    val isEnabled: Boolean = true,
    val isPreview: Boolean = true,
    val isSound: Boolean = true,
    val openMenuKey: String? = null,
)

class NotificationsViewModel(private val prefs: PrefsRepository, private val openWith: OpenWith, private val navigator: Navigator) : ViewModel() {
    private val menus = OpenMenu()

    val state: StateFlow<NotificationsUiState> = combine(prefs.prefs, menus.key, ::build)
        .uiState(viewModelScope, build(prefs.prefs.value, null))

    private fun build(p: Prefs, key: String?) = NotificationsUiState(
        prefs = p.core.notifications,
        isEnabled = p.app.notificationsEnabled,
        isPreview = p.app.notificationPreview,
        isSound = p.app.notificationSound,
        openMenuKey = key,
    )

    fun openMenu(key: String) = menus.open(key)

    fun closeMenu() = menus.close()

    fun toggleEnabled() = prefs.update { it.copy(app = it.app.copy(notificationsEnabled = !it.app.notificationsEnabled)) }
    fun togglePreview() = prefs.update { it.copy(app = it.app.copy(notificationPreview = !it.app.notificationPreview)) }
    fun toggleSound() = prefs.update { it.copy(app = it.app.copy(notificationSound = !it.app.notificationSound)) }
    fun update(transform: (NotificationPrefs) -> NotificationPrefs) = prefs.update { it.copy(core = it.core.copy(notifications = transform(it.core.notifications))) }
    fun setQuietFrom(hour: Int, minute: Int) = update { it.copy(quietHours = it.quietHours.copy(fromHour = hour, fromMinute = minute)) }
    fun setQuietTo(hour: Int, minute: Int) = update { it.copy(quietHours = it.quietHours.copy(toHour = hour, toMinute = minute)) }
    fun openTunnelNotificationSettings() = openWith.openChannelSettings(TnCoreService.CHANNEL_ID)
    fun openSystemSettings() = openWith.openAppNotificationSettings()
    /** This phone's three and the shared settings; Dex's own three are left alone. */
    fun resetToDefaults() = prefs.update {
        val defaults = AppProfile()
        it.copy(
            core = it.core.copy(notifications = NotificationPrefs()),
            app = it.app.copy(
                notificationsEnabled = defaults.notificationsEnabled,
                notificationPreview = defaults.notificationPreview,
                notificationSound = defaults.notificationSound,
            ),
        )
    }
    fun goBack() = navigator.pop()
}

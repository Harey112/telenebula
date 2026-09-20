package com.telenebula.app.ui.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Navigator
import com.telenebula.app.platform.OpenWith
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.ui.shared.OpenMenu
import com.telenebula.app.ui.shared.uiState
import com.telenebula.core.model.NotificationPrefs
import com.telenebula.core.service.TnCoreService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class NotificationsUiState(val prefs: NotificationPrefs, val openMenuKey: String? = null)

class NotificationsViewModel(private val prefs: PrefsRepository, private val openWith: OpenWith, private val navigator: Navigator) : ViewModel() {
    private val menus = OpenMenu()

    val state: StateFlow<NotificationsUiState> = combine(prefs.prefs, menus.key) { p, key -> NotificationsUiState(p.notifications, key) }
        .uiState(viewModelScope, NotificationsUiState(prefs.prefs.value.notifications))

    fun openMenu(key: String) = menus.open(key)

    fun closeMenu() = menus.close()

    fun update(transform: (NotificationPrefs) -> NotificationPrefs) = prefs.update { it.copy(notifications = transform(it.notifications)) }
    fun setQuietFrom(key: String) = key.toIntOrNull()?.let { h -> update { it.copy(quietHours = it.quietHours.copy(fromHour = h)) } } ?: Unit
    fun setQuietTo(key: String) = key.toIntOrNull()?.let { h -> update { it.copy(quietHours = it.quietHours.copy(toHour = h)) } } ?: Unit
    fun openTunnelNotificationSettings() = openWith.openChannelSettings(TnCoreService.CHANNEL_ID)
    fun openSystemSettings() = openWith.openAppNotificationSettings()
    fun resetToDefaults() = prefs.update { it.copy(notifications = NotificationPrefs()) }
    fun goBack() = navigator.pop()
}

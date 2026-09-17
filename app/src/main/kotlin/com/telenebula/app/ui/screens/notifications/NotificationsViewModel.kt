package com.telenebula.app.ui.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Navigator
import com.telenebula.app.platform.OpenWith
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.app.ui.shared.uiState
import com.telenebula.core.CoreClient
import com.telenebula.core.model.NotificationPrefs
import com.telenebula.core.model.Prefs
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

data class NotificationsUiState(
    val notifications: NotificationPrefs = NotificationPrefs(),
    val isTunnelNotificationHidden: Boolean = false,
)

class NotificationsViewModel(
    private val runtime: AppRuntime,
    private val prefs: PrefsRepository,
    private val core: CoreClient,
    private val openWith: OpenWith,
    private val navigator: Navigator,
) : ViewModel() {
    val state: StateFlow<NotificationsUiState> = prefs.prefs.map(::build).uiState(viewModelScope, build(prefs.prefs.value))

    private fun build(p: Prefs) = NotificationsUiState(
        notifications = p.notifications,
        isTunnelNotificationHidden = !p.isBackgroundConnectionEnabled,
    )

    fun update(transform: (NotificationPrefs) -> NotificationPrefs) = prefs.update { it.copy(notifications = transform(it.notifications)) }
    fun setQuietFrom(key: String) = key.toIntOrNull()?.let { h -> update { it.copy(quietHours = it.quietHours.copy(fromHour = h)) } } ?: Unit
    fun setQuietTo(key: String) = key.toIntOrNull()?.let { h -> update { it.copy(quietHours = it.quietHours.copy(toHour = h)) } } ?: Unit

    fun toggleTunnelNotification() {
        val hide = prefs.prefs.value.isBackgroundConnectionEnabled
        prefs.update { it.copy(isBackgroundConnectionEnabled = !hide) }
        if (hide) core.stopBackgroundService() else if (runtime.tunnelRunning.value) core.startBackgroundService()
    }

    fun openSystemSettings() = openWith.openAppNotificationSettings()
    fun resetToDefaults() = prefs.update { it.copy(notifications = NotificationPrefs()) }
    fun goBack() = navigator.pop()
}

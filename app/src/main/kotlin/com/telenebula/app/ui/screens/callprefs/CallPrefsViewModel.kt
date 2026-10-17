package com.telenebula.app.ui.screens.callprefs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.LaunchRequests
import com.telenebula.app.nav.Navigator
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

data class CallPrefsUiState(val isOverlayPermitted: Boolean = false)

class CallPrefsViewModel(
    launch: LaunchRequests,
    private val openOverlaySettings: () -> Unit,
    private val navigator: Navigator,
) : ViewModel() {
    val uiState: StateFlow<CallPrefsUiState> = launch.overlayPermitted.map(::CallPrefsUiState)
        .uiState(viewModelScope, CallPrefsUiState(launch.overlayPermitted.value))

    fun openOverlayPermission() = openOverlaySettings()
    fun goBack() = navigator.pop()
}

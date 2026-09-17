package com.telenebula.app.ui.screens.callprefs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.LaunchRequests
import com.telenebula.app.nav.Navigator
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class CallPrefsUiState(val isVideoSpeakerDefault: Boolean = false, val isOverlayPermitted: Boolean = false)

class CallPrefsViewModel(
    private val prefs: PrefsRepository,
    launch: LaunchRequests,
    private val openOverlaySettings: () -> Unit,
    private val navigator: Navigator,
) : ViewModel() {
    val uiState: StateFlow<CallPrefsUiState> = combine(prefs.prefs, launch.overlayPermitted) { p, overlay ->
        CallPrefsUiState(p.isVideoSpeakerDefault, overlay)
    }.uiState(
        viewModelScope,
        CallPrefsUiState(prefs.prefs.value.isVideoSpeakerDefault, launch.overlayPermitted.value),
    )

    fun toggleVideoSpeakerDefault() = prefs.update { it.copy(isVideoSpeakerDefault = !it.isVideoSpeakerDefault) }

    fun openOverlayPermission() = openOverlaySettings()
    fun goBack() = navigator.pop()
}

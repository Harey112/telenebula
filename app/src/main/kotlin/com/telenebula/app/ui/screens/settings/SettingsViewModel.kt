package com.telenebula.app.ui.screens.settings

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.model.CertExpiryLevel
import com.telenebula.app.nav.Navigator
import com.telenebula.app.nav.TnKey
import com.telenebula.app.platform.CertInspector
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.app.runtime.UpdateMonitor
import com.telenebula.app.ui.shared.uiState
import com.telenebula.core.model.Profile
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class SettingsUiState(val accountSubtitle: String = "", val hasUpdate: Boolean = false)

@Stable
interface SettingsActions {
    fun open(key: TnKey)
    fun goBack()
}

class SettingsViewModel(runtime: AppRuntime, updates: UpdateMonitor, private val navigator: Navigator) : ViewModel(), SettingsActions {
    val uiState: StateFlow<SettingsUiState> = combine(runtime.profile, updates.isUpdateAvailable, ::buildState)
        .uiState(viewModelScope, buildState(runtime.profile.value, updates.isUpdateAvailable.value))

    private fun buildState(profile: Profile?, hasUpdate: Boolean): SettingsUiState {
        val expiry = profile?.let { CertInspector.expiryStatus(it.certNotAfter) }
        return SettingsUiState(
            accountSubtitle = if (expiry != null && expiry.level != CertExpiryLevel.OK) {
                "Certificate: ${expiry.text.lowercase()}"
            } else {
                "Username, IPv6 number, certificate, reset"
            },
            hasUpdate = hasUpdate,
        )
    }

    override fun open(key: TnKey) = navigator.push(key)

    override fun goBack() {
        navigator.pop()
    }
}

package com.telenebula.app.ui.screens.me

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Navigator
import com.telenebula.app.nav.TnKey
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.platform.QrPayloads
import com.telenebula.app.platform.userMessage
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.app.runtime.UpdateMonitor
import com.telenebula.app.ui.shared.uiState
import com.telenebula.core.CoreClient
import com.telenebula.core.model.ChatSummary
import com.telenebula.core.model.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MeUiState(
    val certName: String = "",
    val overlayIp: String = "",
    val isOnline: Boolean = false,
    val isQrOpen: Boolean = false,
    val qrValue: String = "",
    val isVpnRunning: Boolean = false,
    val isBusy: Boolean = false,
    val archivedCount: Int = 0,
    val hasUpdate: Boolean = false,
)

@Stable
interface MeActions {
    fun openQr()
    fun closeQr()
    fun toggleVpn()
    fun open(key: TnKey)
}

class MeViewModel(
    private val runtime: AppRuntime,
    private val prefs: PrefsRepository,
    private val core: CoreClient,
    private val notices: NoticeCenter,
    private val updates: UpdateMonitor,
    private val navigator: Navigator,
) : ViewModel(), MeActions {
    private data class Local(val isQrOpen: Boolean = false, val isBusy: Boolean = false)

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<MeUiState> = combine(runtime.profile, runtime.tunnelRunning, core.chatSummaries, updates.isUpdateAvailable, local, ::build)
        .uiState(viewModelScope, build(runtime.profile.value, runtime.tunnelRunning.value, core.chatSummaries.value, updates.isUpdateAvailable.value, local.value))

    private fun build(profile: Profile?, running: Boolean, chats: List<ChatSummary>?, hasUpdate: Boolean, l: Local) = MeUiState(
        certName = profile?.certName.orEmpty(),
        overlayIp = profile?.overlayIp.orEmpty(),
        isOnline = running,
        isQrOpen = l.isQrOpen,
        qrValue = QrPayloads.encode(profile?.certName.orEmpty(), profile?.overlayIp.orEmpty()),
        isVpnRunning = running,
        isBusy = l.isBusy,
        archivedCount = chats.orEmpty().count { it.isArchived && it.lastTs != null },
        hasUpdate = hasUpdate,
    )

    override fun openQr() = local.update { it.copy(isQrOpen = true) }

    override fun closeQr() = local.update { it.copy(isQrOpen = false) }

    override fun toggleVpn() {
        val profile = runtime.profile.value ?: return
        if (local.value.isBusy) return
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            try {
                if (runtime.tunnelRunning.value) {
                    runtime.stopVpn()
                } else {
                    val granted = notices.withLoading("Starting the nebula tunnel…") { runtime.ensureVpn(profile) }
                    if (!granted) {
                        notices.addWarning("VPN permission denied: Allow the VPN request to bring the tunnel up.")
                    } else if (prefs.prefs.value.isBackgroundConnectionEnabled) {
                        core.startBackgroundService()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addError("Couldn't start the tunnel: ${e.userMessage()}")
            } finally {
                local.update { it.copy(isBusy = false) }
            }
        }
    }

    override fun open(key: TnKey) = navigator.push(key)
}

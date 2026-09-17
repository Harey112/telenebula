package com.telenebula.app.ui.screens.lighthouse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Navigator
import com.telenebula.app.nebula.NebulaDraftStore
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.platform.Endpoints
import com.telenebula.app.platform.IdentityStore
import com.telenebula.app.platform.userMessage
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.app.ui.screens.network.NetworkViewModel
import com.telenebula.core.model.LighthouseEntry
import com.telenebula.core.model.NebulaAdvancedConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LighthouseUiState(val nebulaIp: String = "", val publicHost: String = "", val publicPort: String = "", val isBusy: Boolean = false) {
    val canSave: Boolean get() = nebulaIp.isNotBlank() && Endpoints.isValid(publicHost, publicPort)
}

class LighthouseViewModel(
    private val runtime: AppRuntime,
    private val identity: IdentityStore,
    private val draftStore: NebulaDraftStore,
    private val notices: NoticeCenter,
    private val navigator: Navigator,
) : ViewModel() {
    private val mutable = MutableStateFlow(
        runtime.profile.value?.lighthouses?.firstOrNull().let { stored ->
            val endpoint = Endpoints.split(stored?.underlay.orEmpty())
            LighthouseUiState(stored?.nebulaIp.orEmpty(), endpoint.host, endpoint.port)
        },
    )
    val uiState: StateFlow<LighthouseUiState> = mutable.asStateFlow()

    init {
        // the advanced draft is shared with setup; load this identity's saved settings
        val profile = runtime.profile.value
        if (profile != null) draftStore.hydrateFrom(profile.nebula, profile.msgPort)
    }

    fun setNebulaIp(value: String) = mutable.update { it.copy(nebulaIp = value) }
    fun setPublicHost(value: String) = mutable.update { it.copy(publicHost = value) }
    fun setPublicPort(value: String) = mutable.update { it.copy(publicPort = value.filter(Char::isDigit)) }

    fun save() {
        val profile = runtime.profile.value ?: return
        if (mutable.value.isBusy || !mutable.value.canSave) return
        mutable.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            try {
                val advanced = draftStore.commit()
                if (advanced.errors.isNotEmpty()) {
                    notices.addError("Advanced nebula settings have ${advanced.errors.size} invalid field(s); they are marked in red.")
                    return@launch
                }
                val fields = mutable.value
                val next = profile.copy(
                    lighthouses = listOf(LighthouseEntry(fields.nebulaIp.trim(), Endpoints.join(fields.publicHost, fields.publicPort))),
                    listenPort = advanced.config.listen.port,
                    nebula = advanced.config,
                )
                val needsRestart = needsRestart(profile.nebula, advanced.config)
                identity.saveProfile(next)
                runtime.updateProfile(next)
                if (!runtime.tunnelRunning.value) {
                    notices.setSuccess("Settings saved. They apply when the tunnel connects.")
                    return@launch
                }
                notices.withLoading("Applying nebula settings…") {
                    if (needsRestart) {
                        runtime.stopVpn()
                        delay(NetworkViewModel.RESTART_GAP_MS)
                        runtime.ensureVpn(next)
                    } else {
                        runtime.reloadTunnelConfig(next)
                    }
                }
                notices.setSuccess(
                    if (needsRestart) "Settings saved. The tunnel was restarted with them." else "Settings saved and applied to the running tunnel.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addError("Couldn't apply the settings: ${e.userMessage()}")
            } finally {
                mutable.update { it.copy(isBusy = false) }
            }
        }
    }

    fun goBack() = navigator.pop()

    private companion object {
        /** listen, routines and the tun geometry only apply on a fresh start; everything else reloads live */
        fun needsRestart(previous: NebulaAdvancedConfig, next: NebulaAdvancedConfig): Boolean =
            previous.listen != next.listen ||
                previous.routines != next.routines ||
                previous.tun.unsafeRoutes != next.tun.unsafeRoutes ||
                previous.tun.mtu != next.tun.mtu
    }
}

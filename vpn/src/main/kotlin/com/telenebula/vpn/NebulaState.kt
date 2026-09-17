package com.telenebula.vpn

import com.telenebula.vpn.model.NebulaTunnelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide nebula state. The service writes it, the controller reads it.
 * Both live in the same process.
 */
object NebulaState {
    private val mutableState = MutableStateFlow(NebulaTunnelState())
    val state: StateFlow<NebulaTunnelState> = mutableState.asStateFlow()

    /** the live instance while the tunnel runs — for hostmap/diagnostics queries */
    @Volatile var instance: mobileNebula.Nebula? = null
    /** epoch ms when the tunnel came up, 0 while down */
    @Volatile var startedAt: Long = 0
    @Volatile var logPath: String? = null

    val running: Boolean get() = mutableState.value.running
    val lastError: String? get() = mutableState.value.error

    fun emit(running: Boolean, error: String? = null) {
        // a failed start can publish not-running before stopVpn clears startedAt
        if (!running) startedAt = 0
        mutableState.value = NebulaTunnelState(running = running, error = error, startedAt = startedAt)
    }
}

package com.telenebula.app.ui.screens.call

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.LaunchRequests
import com.telenebula.app.nav.Navigator
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.platform.ActivityGateway
import com.telenebula.app.platform.deniedCallPermission
import com.telenebula.app.platform.deniedCameraPermission
import com.telenebula.app.runtime.CallPresence
import com.telenebula.app.runtime.PresenceState
import com.telenebula.calls.CallEngine
import com.telenebula.calls.CallPhase
import com.telenebula.calls.CallSeat
import com.telenebula.calls.CallState
import com.telenebula.calls.RemoteClient
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.VideoTrack

data class CallUiState(
    val phase: CallPhase? = null,
    val peerName: String = "",
    val isVideoCall: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val camOff: Boolean = true,
    /** the self-view mirrors only for the front camera; the back one must read the right way round */
    val isFrontCamera: Boolean = true,
    val localVideo: VideoTrack? = null,
    val remoteVideo: VideoTrack? = null,
    val statusLabel: String = "",
    /** an ended call that stays on screen with retry/close */
    val hasFailed: Boolean = false,
    val areControlsShown: Boolean = true,
    val isOverlayPromptOpen: Boolean = false,
    /** an active phone call, not moving, with at least one Dex browser to move it to */
    val canMoveToDex: Boolean = false,
    val isMovingToDex: Boolean = false,
    val dexClients: List<RemoteClient> = emptyList(),
    val isDexPickerOpen: Boolean = false,
) {
    val isRemoteVideoLive: Boolean get() = remoteVideo != null && (phase == CallPhase.ACTIVE || phase == CallPhase.CONNECTING)
    val showLocalVideo: Boolean get() = !camOff && localVideo != null && phase != null
    val canMinimize: Boolean get() = phase == CallPhase.CONTACTING || phase == CallPhase.RINGING || phase == CallPhase.CONNECTING || phase == CallPhase.ACTIVE
}

@Stable
interface CallActions {
    fun accept()
    fun decline()
    val egl: EglBase.Context
    fun end()
    fun exit()
    fun minimize()
    fun retry()
    fun switchCamera()
    fun toggleCamera()
    fun toggleControls()
    fun toggleMute()
    fun toggleSpeaker()
    fun moveToDex()
}

class CallViewModel(
    private val engine: CallEngine,
    presence: CallPresence,
    private val launch: LaunchRequests,
    private val gateway: ActivityGateway,
    private val notices: NoticeCenter,
    private val openOverlaySettings: () -> Unit,
    private val navigator: Navigator,
) : ViewModel(), CallActions {
    private data class Local(val areControlsShown: Boolean = true, val isOverlayPromptOpen: Boolean = false, val isDexPickerOpen: Boolean = false)

    private val local = MutableStateFlow(Local())
    override val egl: EglBase.Context get() = engine.eglContext

    val uiState: StateFlow<CallUiState> = combine(engine.state, presence.state, engine.remoteClients, local, ::buildState)
        .uiState(viewModelScope, buildState(engine.state.value, presence.state.value, engine.remoteClients.value, local.value))

    // engine.state/presence.state are already-live StateFlows by the time this ViewModel is built
    // (even on a revisit, after WhileSubscribed tore the combine down) — seeding with this same
    // mapping instead of a blank CallUiState() is what stops a live call from flashing "Call ended"
    // for a frame every time the call screen (re)opens.
    private fun buildState(state: CallState, p: PresenceState, clients: List<RemoteClient>, l: Local): CallUiState = when (state) {
        is CallState.Live -> {
            val isMoving = state.session.movingTo != null
            CallUiState(
                phase = state.phase,
                peerName = state.session.peer.name,
                isVideoCall = state.session.video,
                muted = state.session.muted,
                speaker = state.session.speaker,
                camOff = state.session.camOff,
                isFrontCamera = state.session.isFrontCamera,
                localVideo = state.session.localVideo,
                remoteVideo = if (state.session.remoteCamOn) state.session.remoteVideo else null,
                statusLabel = if (isMoving) "Moving to Dex…" else p.statusLabel,
                areControlsShown = l.areControlsShown,
                isOverlayPromptOpen = l.isOverlayPromptOpen,
                canMoveToDex = state.phase == CallPhase.ACTIVE && state.session.seat == CallSeat.Phone && !isMoving && clients.isNotEmpty(),
                isMovingToDex = isMoving,
                dexClients = clients,
                isDexPickerOpen = l.isDexPickerOpen && clients.isNotEmpty(),
            )
        }
        is CallState.Ended -> CallUiState(
            peerName = state.peer?.name.orEmpty(),
            isVideoCall = state.video,
            statusLabel = state.reason ?: "Call ended",
            hasFailed = state.reason != null,
            areControlsShown = true,
        )
        CallState.Idle -> CallUiState(statusLabel = "Call ended")
    }

    override fun toggleControls() = local.update { it.copy(areControlsShown = !it.areControlsShown) }

    override fun accept() {
        val video = (engine.state.value as? CallState.Live)?.session?.video ?: false
        viewModelScope.launch {
            gateway.deniedCallPermission(video)?.let { denied ->
                notices.addWarning(denied.needed("answer a call"))
                engine.reject()
                return@launch
            }
            engine.accept()
        }
    }

    override fun decline() = engine.reject()
    override fun end() = engine.hangup()
    override fun toggleMute() = engine.toggleMute()
    override fun toggleSpeaker() = engine.toggleSpeaker()
    override fun switchCamera() = engine.switchCamera()
    /** Turning the camera on during an audio call is the one path that never asked for it before. */
    override fun toggleCamera() {
        val isTurningOn = (engine.state.value as? CallState.Live)?.session?.camOff == true
        if (!isTurningOn) {
            engine.toggleCamera()
            return
        }
        viewModelScope.launch {
            gateway.deniedCameraPermission()?.let { denied ->
                notices.addWarning(denied.needed("turn the camera on"))
                return@launch
            }
            engine.toggleCamera()
        }
    }

    override fun retry() {
        val ended = engine.state.value as? CallState.Ended ?: return
        val peer = ended.peer ?: return
        viewModelScope.launch {
            gateway.deniedCallPermission(ended.video)?.let { denied ->
                notices.addWarning(denied.needed("start a call"))
                return@launch
            }
            engine.startCall(peer.ip, ended.video)
        }
    }

    /** Clears the ended state so a later visit does not resurrect the ended screen. */
    override fun exit() {
        engine.acknowledgeEnded()
        navigator.closeCall()
    }

    /** Asked every time it is off: the overlay is the only place a minimised video call is visible. */
    override fun minimize() {
        val live = engine.state.value as? CallState.Live
        val hasVideo = live != null && (!live.session.camOff || live.session.remoteCamOn)
        if (hasVideo && !launch.overlayPermitted.value) {
            local.update { it.copy(isOverlayPromptOpen = true) }
            return
        }
        navigator.closeCall()
    }

    /** One browser moves at once; several open the picker. */
    override fun moveToDex() {
        val clients = uiState.value.dexClients
        if (!uiState.value.canMoveToDex) return
        when (clients.size) {
            0 -> Unit
            1 -> engine.moveToRemote(clients.single().id)
            else -> local.update { it.copy(isDexPickerOpen = true) }
        }
    }

    fun moveToDexClient(clientId: String) {
        local.update { it.copy(isDexPickerOpen = false) }
        if (uiState.value.canMoveToDex) engine.moveToRemote(clientId)
    }

    fun closeDexPicker() = local.update { it.copy(isDexPickerOpen = false) }

    fun allowOverlay() {
        local.update { it.copy(isOverlayPromptOpen = false) }
        openOverlaySettings()
    }

    fun declineOverlay() {
        local.update { it.copy(isOverlayPromptOpen = false) }
        navigator.closeCall()
    }
}

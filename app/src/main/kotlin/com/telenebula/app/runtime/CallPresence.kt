package com.telenebula.app.runtime

import com.telenebula.app.nav.Call
import com.telenebula.app.nav.Navigator
import com.telenebula.app.nav.TnKey
import com.telenebula.app.platform.Format
import com.telenebula.calls.CallEngine
import com.telenebula.calls.CallPhase
import com.telenebula.calls.CallSeat
import com.telenebula.calls.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** What the minimized-call presences (audio banner, video window, floating window) show. */
data class PresenceState(
    val isLive: Boolean = false,
    /** a live call while another screen is in front */
    val isOffCallScreen: Boolean = false,
    val hasVideo: Boolean = false,
    val peerName: String = "",
    val statusLabel: String = "",
    val live: CallState.Live? = null,
    val seat: CallSeat = CallSeat.Phone,
    /** the media is on a Dex browser: this phone only shows a banner */
    val isRemoteSeat: Boolean = false,
)

/** Ticks once a second only while a call is active; otherwise a single value. Shared by every consumer. */
class CallPresence(callEngine: CallEngine, navigator: Navigator, scope: CoroutineScope) {
    private val label = callEngine.state.flatMapLatest { state ->
        val live = state as? CallState.Live
        when (live?.phase) {
            CallPhase.ACTIVE -> flow {
                while (true) {
                    emit(prefix(live) + Format.duration(live.startedAt))
                    delay(1_000)
                }
            }
            else -> flowOf(currentLabel(state))
        }
    }

    private fun prefix(live: CallState.Live): String = if (live.session.isRemoteSeat) "On Dex · " else ""

    val state: StateFlow<PresenceState> = combine(callEngine.state, navigator.topKey, label, ::buildState)
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5_000),
            // callEngine.state/navigator.topKey are already-live StateFlows now — seeding blank is
            // what flashes "no call" for a frame every time a presence consumer (re)subscribes
            // (banner, floating window) while a call is already live, since WhileSubscribed tears
            // this combine down 5s after the last consumer goes away.
            buildState(callEngine.state.value, navigator.topKey.value, currentLabel(callEngine.state.value)),
        )

    private fun currentLabel(state: CallState): String {
        val live = state as? CallState.Live
        return when (live?.phase) {
            CallPhase.ACTIVE -> prefix(live) + Format.duration(live.startedAt)
            CallPhase.CONTACTING -> prefix(live) + "Contacting…"
            CallPhase.RINGING -> prefix(live) + "Ringing…"
            CallPhase.CONNECTING -> prefix(live) + "Connecting…"
            CallPhase.INCOMING -> if (live.session.video) "Incoming video call" else "Incoming call"
            null -> ""
        }
    }

    private fun buildState(state: CallState, top: TnKey?, text: String): PresenceState {
        val live = state as? CallState.Live
        return if (live == null || live.phase == CallPhase.INCOMING) {
            PresenceState(statusLabel = text, live = live, peerName = live?.session?.peer?.name.orEmpty())
        } else {
            val isRemote = live.session.isRemoteSeat
            PresenceState(
                isLive = true,
                isOffCallScreen = isRemote || top != Call,
                hasVideo = !isRemote && (live.session.remoteCamOn || !live.session.camOff),
                peerName = live.session.peer.name,
                statusLabel = text,
                live = live,
                seat = live.session.seat,
                isRemoteSeat = isRemote,
            )
        }
    }
}

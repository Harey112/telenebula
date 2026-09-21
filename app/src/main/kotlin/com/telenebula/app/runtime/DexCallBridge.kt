package com.telenebula.app.runtime

import com.telenebula.calls.CallEngine
import com.telenebula.calls.CallPhase
import com.telenebula.calls.CallSeat
import com.telenebula.calls.CallState
import com.telenebula.calls.IceCandidatePayload
import com.telenebula.calls.RemoteClient
import com.telenebula.calls.RemoteCommand
import com.telenebula.calls.RemoteSeatPort
import com.telenebula.calls.SeatEvent
import com.telenebula.dex.DexCallCommand
import com.telenebula.dex.DexCallEvent
import com.telenebula.dex.DexClient
import com.telenebula.dex.wire.DexCallPeer
import com.telenebula.dex.wire.DexCallPhase
import com.telenebula.dex.wire.DexCallState
import com.telenebula.dex.wire.DexIceCandidate
import com.telenebula.dex.wire.DexSeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The call engine's remote seat and the Dex server's call surface, translated both ways. The two
 * libraries know nothing of each other, so the mapping lives here, in the app that depends on both.
 */
class DexCallBridge(private val scope: CoroutineScope) : RemoteSeatPort {
    private val mutableClients = MutableStateFlow<List<RemoteClient>>(emptyList())
    override val clients: StateFlow<List<RemoteClient>> = mutableClients.asStateFlow()

    private val events = MutableSharedFlow<DexCallEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val callEvents: Flow<DexCallEvent> = events.asSharedFlow()

    private var engine: CallEngine? = null
    private var stateFlow: StateFlow<DexCallState>? = null

    /** The engine is built with this port, so it attaches after construction; once. */
    fun attach(engine: CallEngine) {
        if (this.engine != null) return
        this.engine = engine
        stateFlow = engine.state.map(::project).stateIn(scope, SharingStarted.Eagerly, project(engine.state.value))
    }

    val callState: StateFlow<DexCallState> get() = stateFlow ?: MutableStateFlow(DexCallState())

    fun setClients(list: List<DexClient>) {
        mutableClients.value = list.map { RemoteClient(it.id, labelOf(it)) }
    }

    override fun send(event: SeatEvent) {
        events.tryEmit(
            when (event) {
                is SeatEvent.Media -> DexCallEvent.Media(event.clientId, event.callId, event.isOfferer, event.video, event.remoteSdp, event.remoteSdpType, event.isRestart)
                is SeatEvent.Sdp -> DexCallEvent.Sdp(event.clientId, event.callId, event.sdp, event.sdpType)
                is SeatEvent.Ice -> DexCallEvent.Ice(event.clientId, event.callId, event.candidate?.let { DexIceCandidate(it.candidate, it.sdpMid, it.sdpMLineIndex) })
                is SeatEvent.Release -> DexCallEvent.Release(event.clientId, event.callId, event.reason)
            },
        )
    }

    fun onCommand(command: DexCallCommand) {
        val target = engine ?: return
        target.onRemote(
            when (command) {
                is DexCallCommand.Start -> RemoteCommand.Start(command.clientId, command.peer, command.video)
                is DexCallCommand.Accept -> RemoteCommand.Accept(command.clientId, command.callId)
                is DexCallCommand.Reject -> RemoteCommand.Reject(command.clientId, command.callId)
                is DexCallCommand.End -> RemoteCommand.Hangup(command.clientId, command.callId)
                is DexCallCommand.Sdp -> RemoteCommand.Sdp(command.clientId, command.callId, command.sdp, command.sdpType)
                is DexCallCommand.Ice -> RemoteCommand.Ice(command.clientId, command.callId, command.candidate?.let { IceCandidatePayload(it.candidate, it.sdpMid, it.sdpMLineIndex) })
                is DexCallCommand.Connected -> RemoteCommand.Connected(command.clientId, command.callId)
                is DexCallCommand.Failed -> RemoteCommand.Failed(command.clientId, command.callId, command.reason)
                is DexCallCommand.Cam -> RemoteCommand.Cam(command.clientId, command.callId, command.isOn)
                is DexCallCommand.MoveToPhone -> RemoteCommand.MoveToPhone(command.clientId, command.callId)
                is DexCallCommand.Gone -> RemoteCommand.Gone(command.clientId)
            },
        )
    }

    private fun project(state: CallState): DexCallState = when (state) {
        CallState.Idle -> DexCallState()
        is CallState.Ended -> DexCallState(
            phase = DexCallPhase.ENDED,
            peer = state.peer?.let { DexCallPeer(it.ip, it.name) },
            video = state.video,
            endedReason = state.reason,
        )
        is CallState.Live -> {
            val s = state.session
            val seat = s.seat
            DexCallState(
                phase = when (state.phase) {
                    CallPhase.INCOMING -> DexCallPhase.INCOMING
                    CallPhase.CONTACTING -> DexCallPhase.CONTACTING
                    CallPhase.RINGING -> DexCallPhase.RINGING
                    CallPhase.CONNECTING -> DexCallPhase.CONNECTING
                    CallPhase.ACTIVE -> DexCallPhase.ACTIVE
                },
                callId = s.callId,
                peer = DexCallPeer(s.peer.ip, s.peer.name),
                video = s.video,
                seat = when {
                    state.phase == CallPhase.INCOMING -> DexSeat.NONE
                    seat is CallSeat.Remote -> DexSeat.DEX
                    else -> DexSeat.PHONE
                },
                seatClientId = (seat as? CallSeat.Remote)?.clientId,
                startedAt = state.startedAt,
                remoteCamOn = s.remoteCamOn,
                movingTo = when (s.movingTo) {
                    null -> null
                    CallSeat.Phone -> DexSeat.PHONE
                    is CallSeat.Remote -> DexSeat.DEX
                },
            )
        }
    }

    companion object {
        /** Media events for a call arrive in bursts of candidates; a browser that cannot keep up resyncs on reconnect. */
        const val EVENT_BUFFER = 256

        /** "Chrome on Windows · 192.168.1.5": enough to tell two browsers apart in a picker. */
        fun labelOf(client: DexClient): String {
            val ua = client.userAgent
            val browser = when {
                "Edg/" in ua -> "Edge"
                "OPR/" in ua -> "Opera"
                "Firefox/" in ua -> "Firefox"
                "Chrome/" in ua -> "Chrome"
                "Safari/" in ua -> "Safari"
                ua.isBlank() -> "Browser"
                else -> "Browser"
            }
            val os = when {
                "Windows" in ua -> "Windows"
                "Mac OS" in ua || "Macintosh" in ua -> "Mac"
                "CrOS" in ua -> "ChromeOS"
                "Android" in ua -> "Android"
                "iPhone" in ua || "iPad" in ua -> "iOS"
                "Linux" in ua -> "Linux"
                else -> ""
            }
            val head = if (os.isEmpty()) browser else "$browser on $os"
            return "$head · ${client.remoteAddress}"
        }
    }
}

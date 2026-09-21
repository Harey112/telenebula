package com.telenebula.calls

import kotlinx.coroutines.flow.StateFlow

/** Where a call's media lives: this phone, or one browser logged in to Dex. */
sealed interface CallSeat {
    data object Phone : CallSeat
    data class Remote(val clientId: String) : CallSeat
}

/** A browser that may hold a call's media. */
data class RemoteClient(val id: String, val label: String)

/** What the engine tells one remote seat about its media; the shared call state travels separately. */
sealed interface SeatEvent {
    val clientId: String
    val callId: String

    /** Open (or, with [isRestart], restart) the peer connection; an answerer gets the remote offer, an offerer sends its offer back. */
    class Media(
        override val clientId: String,
        override val callId: String,
        val isOfferer: Boolean,
        val video: Boolean,
        val remoteSdp: String? = null,
        val remoteSdpType: String? = null,
        val isRestart: Boolean = false,
    ) : SeatEvent

    class Sdp(override val clientId: String, override val callId: String, val sdp: String, val sdpType: String) : SeatEvent

    /** a null candidate is the peer's end-of-candidates marker */
    class Ice(override val clientId: String, override val callId: String, val candidate: IceCandidatePayload?) : SeatEvent

    /** the browser's peer connection is no longer part of the call */
    class Release(override val clientId: String, override val callId: String, val reason: String) : SeatEvent
}

/** What a remote seat asked for or reported; [clientId] is the socket it came from. */
sealed interface RemoteCommand {
    val clientId: String

    class Start(override val clientId: String, val peerIp: String, val video: Boolean) : RemoteCommand
    class Accept(override val clientId: String, val callId: String) : RemoteCommand
    class Reject(override val clientId: String, val callId: String) : RemoteCommand
    class Hangup(override val clientId: String, val callId: String) : RemoteCommand
    /** an offer or answer the browser produced */
    class Sdp(override val clientId: String, val callId: String, val sdp: String, val sdpType: String) : RemoteCommand
    class Ice(override val clientId: String, val callId: String, val candidate: IceCandidatePayload?) : RemoteCommand
    class Connected(override val clientId: String, val callId: String) : RemoteCommand
    class Failed(override val clientId: String, val callId: String, val reason: String) : RemoteCommand
    class Cam(override val clientId: String, val callId: String, val isOn: Boolean) : RemoteCommand
    class MoveToPhone(override val clientId: String, val callId: String) : RemoteCommand
    /** the socket closed; anything it held is gone */
    class Gone(override val clientId: String) : RemoteCommand
}

/** The browsers logged in to Dex, as the engine sees them; the app adapts the Dex server to it. */
interface RemoteSeatPort {
    val clients: StateFlow<List<RemoteClient>>

    /** Delivers to one browser; a browser that is gone is dropped silently and reported through [RemoteCommand.Gone]. */
    fun send(event: SeatEvent)
}

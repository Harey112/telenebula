package com.telenebula.calls

import org.webrtc.VideoTrack

data class CallPeer(val ip: String, val name: String)

enum class CallPhase {
    /** ringing on this device, not yet answered */
    INCOMING,
    /** outgoing: the offer is being delivered, or delivered but not yet acknowledged */
    CONTACTING,
    /** the peer's device acknowledged the offer and is ringing */
    RINGING,
    CONNECTING,
    ACTIVE,
}

/** Everything the UI renders for a live call. Track handles replace the old stream URLs. */
data class CallSession(
    val callId: String,
    val peer: CallPeer,
    /** the call was placed or offered as a video call */
    val video: Boolean,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** local camera off (audio calls start with it off) */
    val camOff: Boolean = !video,
    /** which way the local camera faces; the self-view mirrors only for the front one */
    val isFrontCamera: Boolean = true,
    /** peer's camera, driven by call-cam signals */
    val remoteCamOn: Boolean = video,
    val localVideo: VideoTrack? = null,
    val remoteVideo: VideoTrack? = null,
)

sealed interface CallState {
    data object Idle : CallState

    /**
     * The attempt is over. A null [reason] means "leave the call screen after a beat"; a
     * message keeps the screen up with the reason and a retry, as the RN app did.
     */
    data class Ended(val peer: CallPeer?, val video: Boolean, val reason: String?) : CallState

    data class Live(
        val phase: CallPhase,
        val session: CallSession,
        /** epoch ms the call became ACTIVE, 0 before */
        val startedAt: Long = 0,
    ) : CallState

    val liveOrNull: Live? get() = this as? Live
}

/** Wire strings are the call-log outcome values the core stores. */
enum class CallOutcome(val wire: String) {
    ANSWERED("answered"),
    MISSED("missed"),
    DECLINED("declined"),
    NO_ANSWER("no-answer"),
    UNREACHABLE("unreachable"),
    CANCELLED("cancelled"),
    FAILED("failed"),
}

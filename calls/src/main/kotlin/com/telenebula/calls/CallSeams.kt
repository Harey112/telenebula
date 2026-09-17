package com.telenebula.calls

import com.telenebula.calls.media.WebRtcSessionListener
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * The seams between the call state machine and the platform. The engine only ever talks to these,
 * so the signaling sequence can run under a unit test with fakes; the Android and libwebrtc
 * implementations live in `media/`, `system/` and `audio/`.
 */

/** The microphone track for the whole call and the camera while it is on. */
interface LocalTracks {
    val videoTrack: VideoTrack?

    /** The self-view is mirrored only for the front camera; the back one must read the right way round. */
    val isFrontCamera: Boolean

    /** Opens the camera and returns the live track, or throws when none is usable. */
    fun startCamera(): VideoTrack
    fun stopCamera()

    /** [onSwitched] reports the new facing; the switch is asynchronous and may fail, reporting nothing. */
    fun switchCamera(onSwitched: (isFront: Boolean) -> Unit)
    fun setMicrophoneEnabled(enabled: Boolean)
    fun release()
}

/** One peer connection as the engine sees it. */
interface PeerLink {
    val isStable: Boolean
    suspend fun createOffer(): SessionDescription
    suspend fun createAnswer(): SessionDescription
    suspend fun setRemote(description: SessionDescription)
    fun addRemoteCandidate(candidate: IceCandidate)

    /** Replaces the outgoing video track; true when this added the video m-line and a renegotiation is due. */
    fun setVideoTrack(track: VideoTrack?): Boolean
    fun restartIce()

    /** One line naming the local and remote candidate the media flows over, once known. */
    fun describeSelectedPair(onResult: (String) -> Unit)
    fun close()
}

interface CallMediaFactory {
    val eglContext: EglBase.Context

    /** Microphone, plus the camera when [video]; throws [com.telenebula.calls.media.MediaUnavailableException]. */
    fun openLocal(video: Boolean): LocalTracks

    /** A peer connection carrying [local]'s tracks; [local] is always what [openLocal] returned. */
    fun openLink(listener: WebRtcSessionListener, local: LocalTracks): PeerLink

    /** Mirrors the diagnostics switch into libwebrtc's own logging. */
    fun setVerbose(enabled: Boolean)
}

/** Notifications, the foreground service and the floating window. */
interface CallSystem {
    fun startSession(peerName: String, video: Boolean, connectedAt: Long)
    fun stopSession()
    fun showIncoming(peerName: String, video: Boolean, vibrate: Boolean)
    fun hideIncoming()
    fun hideFloating()
    fun showMissed(peerName: String, video: Boolean)
}

/** The call's audio session and progress tones. */
interface CallAudioPort {
    fun start(speaker: Boolean)
    fun setSpeaker(on: Boolean)
    fun startRingback()
    fun stopRingback()
    fun stop(playBusyTone: Boolean)
    fun setProximityEnabled(enabled: Boolean)
}

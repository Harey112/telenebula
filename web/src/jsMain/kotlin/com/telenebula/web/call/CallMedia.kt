package com.telenebula.web.call

import com.telenebula.web.wire.DexIceCandidate
import com.telenebula.web.wire.DexIceServer
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLVideoElement
import kotlin.js.Promise
import kotlin.js.json

/** What the browser's peer connection reports back to the phone. */
interface CallMediaPort {
    fun onSdp(callId: String, sdp: String, sdpType: String)
    fun onIce(callId: String, candidate: DexIceCandidate?)
    fun onConnected(callId: String)
    fun onFailed(callId: String, reason: String)
    fun onLocalStream(stream: dynamic)
    fun onRemoteStream(stream: dynamic)
}

/**
 * One RTCPeerConnection for the call this browser holds. Relay-only: the phone's TURN is the
 * only route to the peer. Every WebRTC object is `dynamic`, so a browser without the API fails
 * into [CallMediaPort.onFailed] instead of throwing.
 */
class CallMedia(private val scope: CoroutineScope, private val port: CallMediaPort) {
    private var pc: dynamic = null
    private var localStream: dynamic = null
    private var callId: String? = null
    private var pendingCandidates = ArrayList<DexIceCandidate?>()
    private var hasRemoteDescription = false
    private var hasReportedConnected = false
    private var isNegotiating = false
    private var videoSender: dynamic = null
    var isMuted: Boolean = false
        private set
    var isCameraOn: Boolean = false
        private set

    val isActive: Boolean get() = pc != null
    val currentCallId: String? get() = callId

    companion object {
        val isSupported: Boolean
            get() = js("typeof RTCPeerConnection !== 'undefined' && typeof navigator !== 'undefined' && !!navigator.mediaDevices && !!navigator.mediaDevices.getUserMedia").unsafeCast<Boolean>()
    }

    fun open(callId: String, isOfferer: Boolean, video: Boolean, iceServers: List<DexIceServer>, remoteSdp: String?, remoteSdpType: String?, isRestart: Boolean) {
        scope.launch {
            try {
                if (!isSupported) {
                    port.onFailed(callId, "Calls need a secure origin and a modern browser")
                    return@launch
                }
                if (isRestart && pc != null && this@CallMedia.callId == callId) {
                    restart()
                    return@launch
                }
                close()
                this@CallMedia.callId = callId
                val servers = iceServers.map { s ->
                    val o = json("urls" to s.urls.toTypedArray())
                    s.username?.let { o["username"] = it }
                    s.credential?.let { o["credential"] = it }
                    o
                }.toTypedArray()
                val connection: dynamic = newPeerConnection(json("iceServers" to servers, "iceTransportPolicy" to "relay"))
                pc = connection
                wire(connection, callId)
                val stream = try {
                    getUserMedia(video)
                } catch (e: Throwable) {
                    port.onFailed(callId, if (video) "Camera or microphone is not available" else "Microphone is not available")
                    close()
                    return@launch
                }
                if (pc !== connection) {
                    stopStream(stream)
                    return@launch
                }
                localStream = stream
                isCameraOn = video
                isMuted = false
                val tracks: Array<dynamic> = stream.getTracks().unsafeCast<Array<dynamic>>()
                for (t in tracks) {
                    val sender = connection.addTrack(t, stream)
                    if (t.kind == "video") videoSender = sender
                }
                if (!video) {
                    // reserve the m-line so a later camera-on is a track swap the peer already agreed to
                    videoSender = connection.addTransceiver("video", json("direction" to "recvonly")).sender
                }
                port.onLocalStream(stream)
                if (isOfferer) {
                    val offer = (connection.createOffer() as Promise<dynamic>).await()
                    (connection.setLocalDescription(offer) as Promise<dynamic>).await()
                    port.onSdp(callId, connection.localDescription.sdp as String, connection.localDescription.type as String)
                } else {
                    val sdp = remoteSdp
                    if (sdp == null) {
                        port.onFailed(callId, "The call arrived without an offer")
                        close()
                        return@launch
                    }
                    applyRemote(sdp, remoteSdpType ?: "offer")
                }
            } catch (e: Throwable) {
                port.onFailed(callId, e.message ?: "Could not set up the call")
                close()
            }
        }
    }

    private fun newPeerConnection(config: dynamic): dynamic = js("new RTCPeerConnection(config)")

    private suspend fun getUserMedia(video: Boolean): dynamic {
        val constraints = json("audio" to true, "video" to if (video) json("facingMode" to "user") else false)
        return (window.navigator.asDynamic().mediaDevices.getUserMedia(constraints) as Promise<dynamic>).await()
    }

    private fun wire(connection: dynamic, callId: String) {
        connection.onicecandidate = { e: dynamic ->
            val c = e.candidate
            if (c == null) {
                port.onIce(callId, null)
            } else {
                val candidate = c.candidate as? String
                if (candidate != null && candidate.isNotEmpty()) {
                    port.onIce(callId, DexIceCandidate(candidate, c.sdpMid as? String, (c.sdpMLineIndex as? Number)?.toInt()))
                }
            }
            Unit
        }
        connection.ontrack = { e: dynamic ->
            val streams = e.streams.unsafeCast<Array<dynamic>>()
            val stream = if (streams.isNotEmpty()) streams[0] else js("new MediaStream([e.track])")
            port.onRemoteStream(stream)
            Unit
        }
        connection.onconnectionstatechange = {
            when (connection.connectionState as String) {
                "connected" -> if (!hasReportedConnected) {
                    hasReportedConnected = true
                    port.onConnected(callId)
                }
                "failed" -> port.onFailed(callId, "ICE failed")
                "disconnected" -> Unit
                else -> Unit
            }
            Unit
        }
        connection.onnegotiationneeded = {
            if (hasRemoteDescription && !isNegotiating) scope.launch { renegotiate(callId) }
            Unit
        }
    }

    private suspend fun renegotiate(callId: String) {
        val connection = pc ?: return
        if (connection.signalingState != "stable") return
        isNegotiating = true
        try {
            val offer = (connection.createOffer() as Promise<dynamic>).await()
            if (pc !== connection) return
            (connection.setLocalDescription(offer) as Promise<dynamic>).await()
            port.onSdp(callId, connection.localDescription.sdp as String, connection.localDescription.type as String)
        } catch (e: Throwable) {
            console.warn("renegotiation failed", e)
        } finally {
            isNegotiating = false
        }
    }

    private suspend fun restart() {
        val connection = pc ?: return
        val id = callId ?: return
        hasReportedConnected = false
        try {
            connection.restartIce()
            val offer = (connection.createOffer(json("iceRestart" to true)) as Promise<dynamic>).await()
            (connection.setLocalDescription(offer) as Promise<dynamic>).await()
            port.onSdp(id, connection.localDescription.sdp as String, connection.localDescription.type as String)
        } catch (e: Throwable) {
            port.onFailed(id, "Could not restart the connection")
        }
    }

    fun remoteSdp(callId: String, sdp: String, sdpType: String) {
        if (this.callId != callId) return
        scope.launch {
            try {
                applyRemote(sdp, sdpType)
            } catch (e: Throwable) {
                port.onFailed(callId, "The peer's description was refused: ${e.message}")
            }
        }
    }

    private suspend fun applyRemote(sdp: String, sdpType: String) {
        val connection = pc ?: return
        val id = callId ?: return
        if (sdpType == "offer" && connection.signalingState == "have-local-offer") {
            // glare: the phone-side is the tie breaker, so this browser yields its own offer
            (connection.setLocalDescription(json("type" to "rollback")) as Promise<dynamic>).await()
        }
        (connection.setRemoteDescription(json("type" to sdpType, "sdp" to sdp)) as Promise<dynamic>).await()
        hasRemoteDescription = true
        for (c in pendingCandidates) addCandidate(connection, c)
        pendingCandidates.clear()
        if (sdpType == "offer") {
            val answer = (connection.createAnswer() as Promise<dynamic>).await()
            (connection.setLocalDescription(answer) as Promise<dynamic>).await()
            port.onSdp(id, connection.localDescription.sdp as String, connection.localDescription.type as String)
        }
    }

    fun remoteIce(callId: String, candidate: DexIceCandidate?) {
        if (this.callId != callId) return
        val connection = pc ?: return
        if (!hasRemoteDescription) {
            if (pendingCandidates.size < 64) pendingCandidates.add(candidate)
            return
        }
        scope.launch { addCandidate(connection, candidate) }
    }

    private suspend fun addCandidate(connection: dynamic, candidate: DexIceCandidate?) {
        try {
            if (candidate == null) {
                (connection.addIceCandidate(null) as Promise<dynamic>).await()
            } else {
                (connection.addIceCandidate(json("candidate" to candidate.candidate, "sdpMid" to candidate.sdpMid, "sdpMLineIndex" to candidate.sdpMLineIndex)) as Promise<dynamic>).await()
            }
        } catch (e: Throwable) {
            console.warn("candidate refused", e.message)
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        val stream = localStream ?: return
        val tracks = stream.getAudioTracks().unsafeCast<Array<dynamic>>()
        for (t in tracks) t.enabled = !muted
    }

    /** Turns the camera on or off; returns false when no camera could be opened. */
    fun setCamera(on: Boolean, onResult: (Boolean) -> Unit) {
        val connection = pc
        val stream = localStream
        if (connection == null || stream == null) {
            onResult(false)
            return
        }
        scope.launch {
            try {
                if (!on) {
                    val tracks = stream.getVideoTracks().unsafeCast<Array<dynamic>>()
                    for (t in tracks) {
                        t.stop()
                        stream.removeTrack(t)
                    }
                    val sender = videoSender
                    if (sender != null) (sender.replaceTrack(null) as Promise<dynamic>).await()
                    isCameraOn = false
                    port.onLocalStream(stream)
                    onResult(true)
                    return@launch
                }
                val camera = (window.navigator.asDynamic().mediaDevices.getUserMedia(json("video" to json("facingMode" to "user"))) as Promise<dynamic>).await()
                val track = camera.getVideoTracks().unsafeCast<Array<dynamic>>().firstOrNull()
                if (track == null || pc !== connection) {
                    stopStream(camera)
                    onResult(false)
                    return@launch
                }
                stream.addTrack(track)
                val sender = videoSender
                if (sender != null) {
                    (sender.replaceTrack(track) as Promise<dynamic>).await()
                    val transceivers = connection.getTransceivers().unsafeCast<Array<dynamic>>()
                    for (tr in transceivers) if (tr.sender === sender) tr.direction = "sendrecv"
                } else {
                    videoSender = connection.addTrack(track, stream)
                }
                isCameraOn = true
                port.onLocalStream(stream)
                onResult(true)
            } catch (e: Throwable) {
                onResult(false)
            }
        }
    }

    fun close() {
        val connection = pc
        pc = null
        callId = null
        hasRemoteDescription = false
        hasReportedConnected = false
        isNegotiating = false
        videoSender = null
        pendingCandidates.clear()
        val stream = localStream
        localStream = null
        if (stream != null) stopStream(stream)
        isCameraOn = false
        isMuted = false
        if (connection != null) {
            try {
                connection.onicecandidate = null
                connection.ontrack = null
                connection.onconnectionstatechange = null
                connection.onnegotiationneeded = null
                connection.close()
            } catch (e: Throwable) {
                // already closed
            }
        }
    }

    private fun stopStream(stream: dynamic) {
        try {
            val tracks = stream.getTracks().unsafeCast<Array<dynamic>>()
            for (t in tracks) t.stop()
        } catch (e: Throwable) {
            // no tracks left
        }
    }
}

fun HTMLVideoElement.attach(stream: dynamic) {
    try {
        asDynamic().srcObject = stream
        if (stream != null) play().unsafeCast<Promise<Unit>>().then<Unit>({ }, { })
    } catch (e: Throwable) {
        // autoplay refused; the user's next click starts it
    }
}

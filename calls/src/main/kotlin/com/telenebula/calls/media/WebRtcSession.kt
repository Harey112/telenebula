package com.telenebula.calls.media

import com.telenebula.calls.PeerLink
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RTCStats
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/** Engine-facing callbacks; invoked on libwebrtc's signaling thread, so hop before touching state. */
interface WebRtcSessionListener {
    fun onLocalCandidate(candidate: IceCandidate)

    /** The initial gathering round is over; the peer gets an explicit end-of-candidates. */
    fun onLocalGatheringComplete()
    fun onRemoteVideo(track: VideoTrack)
    fun onConnected()

    /** ICE ran out of pairs to try; the engine decides between a restart and giving up. */
    fun onIceFailed()
    fun onConnectionClosed()

    /** Every other transport state change, for the diagnostics trail only. */
    fun onLinkState(what: String)
}

/**
 * One peer connection with the app's fixed configuration: unified plan, no ICE servers (the
 * overlay routes IPv6 directly) and continual gathering, so a tunnel rebind after a wifi↔mobile
 * switch still yields fresh candidates for an ICE restart.
 */
class WebRtcSession(runtime: WebRtcRuntime, private val listener: WebRtcSessionListener) : PeerLink {
    private val config = PeerConnection.RTCConfiguration(emptyList()).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) = listener.onLocalCandidate(candidate)

        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.let(listener::onRemoteVideo)
        }

        // DISCONNECTED is a blip until libwebrtc says FAILED: the tunnel rebinds on network changes
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            listener.onLinkState("pc=$newState")
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> listener.onConnected()
                PeerConnection.PeerConnectionState.FAILED -> listener.onIceFailed()
                PeerConnection.PeerConnectionState.CLOSED -> listener.onConnectionClosed()
                else -> Unit
            }
        }

        // some devices only report ICE state — treat it as the connect signal too
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
            listener.onLinkState("ice=$newState")
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> listener.onConnected()
                PeerConnection.IceConnectionState.FAILED -> listener.onIceFailed()
                else -> Unit
            }
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
            listener.onLinkState("gathering=$newState")
            if (newState == PeerConnection.IceGatheringState.COMPLETE) listener.onLocalGatheringComplete()
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private val pc: PeerConnection = runtime.factory.createPeerConnection(config, observer)
        ?: throw IllegalStateException("PeerConnection could not be created")

    private var isRemoteDescriptionSet = false
    private val queuedRemoteCandidates = ArrayList<IceCandidate>(8)
    private var videoSender: RtpSender? = null

    override val isStable: Boolean get() = pc.signalingState() == PeerConnection.SignalingState.STABLE

    /** Adds the microphone and, for a video call, the camera before the first offer or answer. */
    fun attach(audio: AudioTrack, video: VideoTrack?) {
        pc.addTrack(audio, STREAM_IDS)
        if (video != null) videoSender = pc.addTrack(video, STREAM_IDS)
    }

    override suspend fun createOffer(): SessionDescription = pc.createOfferAwait().also { pc.setLocalDescriptionAwait(it) }

    override suspend fun createAnswer(): SessionDescription = pc.createAnswerAwait().also { pc.setLocalDescriptionAwait(it) }

    /** Applies the remote description and flushes candidates that arrived before it. */
    override suspend fun setRemote(description: SessionDescription) {
        pc.setRemoteDescriptionAwait(description)
        isRemoteDescriptionSet = true
        if (queuedRemoteCandidates.isNotEmpty()) {
            for (candidate in queuedRemoteCandidates) pc.addIceCandidate(candidate)
            queuedRemoteCandidates.clear()
        }
    }

    override fun addRemoteCandidate(candidate: IceCandidate) {
        if (isRemoteDescriptionSet) pc.addIceCandidate(candidate) else queuedRemoteCandidates.add(candidate)
    }

    override fun setVideoTrack(track: VideoTrack?): Boolean {
        val sender = videoSender
        return when {
            sender != null -> {
                sender.setTrack(track, false)
                false
            }
            track == null -> false
            else -> {
                videoSender = pc.addTrack(track, STREAM_IDS)
                true
            }
        }
    }

    override fun restartIce() = pc.restartIce()

    override fun describeSelectedPair(onResult: (String) -> Unit) {
        pc.getStats { report ->
            val stats = report.statsMap.values
            val pair = stats.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
            if (pair == null) {
                onResult("no nominated candidate pair yet")
                return@getStats
            }
            val local = report.statsMap[pair.members["localCandidateId"] as? String]
            val remote = report.statsMap[pair.members["remoteCandidateId"] as? String]
            onResult("local ${local.candidateLabel()} ↔ remote ${remote.candidateLabel()}")
        }
    }

    override fun close() {
        queuedRemoteCandidates.clear()
        runCatching { pc.close() }
        pc.dispose()
    }

    private fun RTCStats?.candidateLabel(): String {
        val m = this?.members ?: return "?"
        val type = m["candidateType"] ?: "?"
        val address = m["address"] ?: m["ip"] ?: "?"
        val port = m["port"] ?: "?"
        val network = m["networkType"]?.let { " $it" } ?: ""
        return "$type [$address]:$port$network"
    }

    private companion object {
        val STREAM_IDS = listOf("tn-stream")
    }
}

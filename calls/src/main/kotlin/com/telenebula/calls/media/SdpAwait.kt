package com.telenebula.calls.media

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

class SdpException(message: String) : Exception(message)

private abstract class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetFailure(error: String?) = Unit
}

internal suspend fun PeerConnection.createOfferAwait(): SessionDescription = createAwait { createOffer(it, MediaConstraints()) }

internal suspend fun PeerConnection.createAnswerAwait(): SessionDescription = createAwait { createAnswer(it, MediaConstraints()) }

internal suspend fun PeerConnection.setLocalDescriptionAwait(description: SessionDescription) =
    setAwait { setLocalDescription(it, description) }

internal suspend fun PeerConnection.setRemoteDescriptionAwait(description: SessionDescription) =
    setAwait { setRemoteDescription(it, description) }

private suspend inline fun createAwait(crossinline call: (SdpObserver) -> Unit): SessionDescription =
    suspendCancellableCoroutine { cont ->
        call(
            object : SdpAdapter() {
                override fun onCreateSuccess(description: SessionDescription) = cont.resume(description)
                override fun onCreateFailure(error: String?) = cont.resumeWithException(SdpException(error ?: "sdp create failed"))
            },
        )
    }

private suspend inline fun setAwait(crossinline call: (SdpObserver) -> Unit): Unit =
    suspendCancellableCoroutine { cont ->
        call(
            object : SdpAdapter() {
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) = cont.resumeWithException(SdpException(error ?: "sdp set failed"))
            },
        )
    }

internal fun sdpType(wire: String?, fallback: SessionDescription.Type): SessionDescription.Type = when (wire) {
    "offer" -> SessionDescription.Type.OFFER
    "answer" -> SessionDescription.Type.ANSWER
    "pranswer" -> SessionDescription.Type.PRANSWER
    "rollback" -> SessionDescription.Type.ROLLBACK
    else -> fallback
}

internal val SessionDescription.Type.wire: String get() = canonicalForm()

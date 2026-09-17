package com.telenebula.calls

/** Signal type strings as they travel in the envelope's `type` field. */
object CallSignalType {
    const val OFFER = "call-offer"
    const val RINGING = "call-ringing"
    const val ANSWER = "call-answer"
    const val ICE = "call-ice"
    const val RENEGOTIATE = "call-renegotiate"
    const val RENEGOTIATE_ANSWER = "call-renegotiate-answer"
    const val CAM = "call-cam"
    const val REJECT = "call-reject"
    const val END = "call-end"
}

data class IceCandidatePayload(val candidate: String, val sdpMid: String? = null, val sdpMLineIndex: Int? = null)

/**
 * One inbound signal, already typed: the app's adapter turns a wire envelope into exactly one of
 * these (or drops it), so the engine's dispatch is an exhaustive `when` with nothing optional in it.
 */
sealed interface InboundSignal {
    val callId: String

    class Offer(override val callId: String, val fromName: String, val sdp: String, val sdpType: String?, val video: Boolean) : InboundSignal
    class Ringing(override val callId: String) : InboundSignal
    class Answer(override val callId: String, val sdp: String, val sdpType: String?) : InboundSignal
    /** a null candidate is the wire's end-of-candidates marker */
    class Ice(override val callId: String, val candidate: IceCandidatePayload?) : InboundSignal
    class Renegotiate(override val callId: String, val sdp: String, val sdpType: String?) : InboundSignal
    class RenegotiateAnswer(override val callId: String, val sdp: String, val sdpType: String?) : InboundSignal
    class Cam(override val callId: String, val isOn: Boolean) : InboundSignal
    class Reject(override val callId: String, val reason: String?) : InboundSignal
    class End(override val callId: String) : InboundSignal

    val sdpOrNull: String? get() = when (this) {
        is Offer -> sdp
        is Answer -> sdp
        is Renegotiate -> sdp
        is RenegotiateAnswer -> sdp
        else -> null
    }
}

/** Outbound body; the core fills in `v`, `id`, `from` and `ts`. */
data class OutboundCallSignal(
    val type: String,
    val callId: String,
    val sdp: String? = null,
    val sdpType: String? = null,
    val video: Boolean? = null,
    val candidate: IceCandidatePayload? = null,
    val reason: String? = null,
)

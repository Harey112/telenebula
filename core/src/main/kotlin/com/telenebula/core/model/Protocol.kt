package com.telenebula.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EnvelopeType {
    @SerialName("hello") HELLO,
    @SerialName("hello-ack") HELLO_ACK,
    @SerialName("msg") MSG,
    @SerialName("msg-ack") MSG_ACK,
    @SerialName("att-begin") ATT_BEGIN,
    @SerialName("att-chunk") ATT_CHUNK,

    /** the sender asks before streaming a large attachment; same payload as att-begin, no commitment */
    @SerialName("att-offer") ATT_OFFER,

    /** go ahead (targetId; seq is the chunk to resume from, 0 for a fresh start) */
    @SerialName("att-accept") ATT_ACCEPT,

    /** do not send (targetId + reason) */
    @SerialName("att-decline") ATT_DECLINE,

    /** the sender withdraws an offer it has not streamed yet (targetId) */
    @SerialName("att-cancel") ATT_CANCEL,

    /** the receiver is abandoning a transfer and says why (targetId + reason) */
    @SerialName("att-error") ATT_ERROR,

    @SerialName("react") REACT,
    @SerialName("edit") EDIT,
    @SerialName("delete") DELETE,

    /** read receipt for one or more messages (targetIds) */
    @SerialName("seen") SEEN,

    /** typing indicator (typing: bool); never stored, never acked */
    @SerialName("typing") TYPING,

    @SerialName("call-offer") CALL_OFFER,

    /** callee acknowledges the offer: its device is ringing now */
    @SerialName("call-ringing") CALL_RINGING,

    @SerialName("call-answer") CALL_ANSWER,
    @SerialName("call-ice") CALL_ICE,
    @SerialName("call-reject") CALL_REJECT,
    @SerialName("call-end") CALL_END,
    @SerialName("call-renegotiate") CALL_RENEGOTIATE,
    @SerialName("call-renegotiate-answer") CALL_RENEGOTIATE_ANSWER,
    @SerialName("call-cam") CALL_CAM,
    @SerialName("ping") PING,
    @SerialName("pong") PONG,

    /** forward compatibility: a type this build does not know is ignored, never answered */
    @SerialName("Unknown") UNKNOWN,
}

@Serializable
data class EnvelopeFrom(val ip: String = "", val name: String = "")

@Serializable
data class IceCandidatePayload(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
)

/** A pre-file-storage inline attachment; only legacy peers still send one. */
@Serializable
data class AttachmentPayload(
    val name: String,
    val mime: String,
    val size: Long = 0,
    val dataB64: String,
)

/**
 * One wire envelope: the complete field set across every type, exactly as v1 of the protocol
 * puts it on the socket. Optional fields are omitted rather than sent as null.
 *
 * A `call-ice` frame carries either a candidate or nothing, and nothing means end-of-candidates —
 * so an absent field and an explicit `null` mean the same thing here and both decode to null.
 */
@Serializable
data class Envelope(
    val v: Int = 0,
    val type: EnvelopeType = EnvelopeType.UNKNOWN,
    /** message id for msg, action id for react/edit/delete; acked via msg-ack */
    val id: String = "",
    val from: EnvelopeFrom = EnvelopeFrom(),
    val ts: Long = 0,
    val body: String? = null,
    val attachment: AttachmentPayload? = null,
    val replyToId: String? = null,
    val name: String? = null,
    val mime: String? = null,
    val size: Long? = null,
    val totalChunks: Long? = null,
    val transferId: String? = null,
    val seq: Long? = null,
    val dataB64: String? = null,
    val ackId: String? = null,
    val targetId: String? = null,
    val targetIds: List<String>? = null,
    val emoji: String? = null,
    val remove: Boolean? = null,
    val newBody: String? = null,
    val sdp: String? = null,
    val sdpType: String? = null,
    val video: Boolean? = null,
    val candidate: IceCandidatePayload? = null,
    val callId: String? = null,
    val reason: String? = null,
    val typing: Boolean? = null,
    /** type=msg / att-begin: disappearing-message timer in seconds */
    val expiresIn: Long? = null,
    /** type=att-begin / att-offer: pixel size of the media, when the sender knew it */
    val width: Long? = null,
    val height: Long? = null,
    /** type=att-begin / att-offer: audio length in ms; absent from older builds */
    val duration: Long? = null,
    /** type=hello / hello-ack: the sender's app version */
    val app: String? = null,
    /** type=pong: "online" while the sender uses the app and shares that, else "reachable"; absent from older builds */
    val presence: String? = null,
)

/** The fields a caller provides for a signal; the core fills v, id, from and ts. */
@Serializable
data class OutboundSignal(
    val type: EnvelopeType,
    val id: String? = null,
    val callId: String? = null,
    val sdp: String? = null,
    val sdpType: String? = null,
    val video: Boolean? = null,
    val candidate: IceCandidatePayload? = null,
    val reason: String? = null,
    val typing: Boolean? = null,
)

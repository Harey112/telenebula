package com.telenebula.app.runtime

import com.telenebula.app.platform.ContactLabels
import com.telenebula.calls.CallContact
import com.telenebula.calls.CallSignalType
import com.telenebula.calls.CallLogEntry
import com.telenebula.calls.CallOutcome
import com.telenebula.calls.CoreSignaling
import com.telenebula.calls.IceCandidatePayload as CallCandidate
import com.telenebula.calls.InboundSignal
import com.telenebula.calls.OutboundCallSignal
import com.telenebula.core.CoreClient
import com.telenebula.core.model.CallLog
import com.telenebula.core.model.Envelope
import com.telenebula.core.model.EnvelopeType
import com.telenebula.core.model.IceCandidatePayload
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.OutboundSignal
import com.telenebula.core.model.CallOutcome as CoreOutcome

/**
 * The call engine's view of the messaging core. The two libraries know nothing of each other, so
 * the translation between their signal types lives here, in the app that depends on both.
 */
class CoreSignalingAdapter(private val core: CoreClient) : CoreSignaling {
    override suspend fun sendSignal(peerIp: String, signal: OutboundCallSignal, timeoutMs: Int) =
        core.sendSignal(peerIp, signal.toCoreSignal(), timeoutMs)

    override suspend fun pingPeer(peerIp: String, timeoutMs: Int): Long = core.pingPeer(peerIp, timeoutMs)

    override suspend fun contact(ip: String): CallContact? = core.contact(ip)?.let {
        CallContact(label = ContactLabels.chatLabel(it), isBlocked = it.isBlocked, callsAllowed = it.notifications?.calls != false)
    }

    override suspend fun ensureContact(ip: String, name: String) = core.ensureContact(ip, name)

    override suspend fun logCall(entry: CallLogEntry) = core.logCall(
        CallLog(
            id = entry.id,
            peerIp = entry.peerIp,
            direction = if (entry.isIncoming) MessageDirection.IN else MessageDirection.OUT,
            isVideo = entry.isVideo,
            outcome = when (entry.outcome) {
                CallOutcome.ANSWERED -> CoreOutcome.ANSWERED
                CallOutcome.MISSED -> CoreOutcome.MISSED
                CallOutcome.DECLINED -> CoreOutcome.DECLINED
                CallOutcome.NO_ANSWER -> CoreOutcome.NO_ANSWER
                CallOutcome.UNREACHABLE -> CoreOutcome.UNREACHABLE
                CallOutcome.CANCELLED -> CoreOutcome.CANCELLED
                CallOutcome.FAILED -> CoreOutcome.FAILED
            },
            startedAt = entry.startedAt,
            connectedAt = entry.connectedAt,
            endedAt = entry.endedAt,
        ),
    )

    override fun normalizeIp(ip: String): String = CoreClient.normalizeIp(ip)

    companion object {
        /** A wire envelope as a call sees it; anything that is not a well-formed call signal is dropped here, at the boundary. */
        fun toInboundSignal(envelope: Envelope): InboundSignal? {
            val callId = envelope.callId?.takeIf { it.isNotEmpty() } ?: return null
            return when (envelope.type) {
                EnvelopeType.CALL_OFFER -> InboundSignal.Offer(callId, envelope.from.name, envelope.sdp ?: return null, envelope.sdpType, envelope.video == true)
                EnvelopeType.CALL_RINGING -> InboundSignal.Ringing(callId)
                EnvelopeType.CALL_ANSWER -> InboundSignal.Answer(callId, envelope.sdp ?: return null, envelope.sdpType)
                EnvelopeType.CALL_ICE -> InboundSignal.Ice(callId, envelope.candidate?.let { CallCandidate(it.candidate, it.sdpMid, it.sdpMLineIndex) })
                EnvelopeType.CALL_RENEGOTIATE -> InboundSignal.Renegotiate(callId, envelope.sdp ?: return null, envelope.sdpType)
                EnvelopeType.CALL_RENEGOTIATE_ANSWER -> InboundSignal.RenegotiateAnswer(callId, envelope.sdp ?: return null, envelope.sdpType)
                EnvelopeType.CALL_CAM -> InboundSignal.Cam(callId, envelope.video == true)
                EnvelopeType.CALL_REJECT -> InboundSignal.Reject(callId, envelope.reason)
                EnvelopeType.CALL_END -> InboundSignal.End(callId)
                else -> null
            }
        }

        private fun OutboundCallSignal.toCoreSignal() = OutboundSignal(
            type = requireNotNull(CALL_TYPES.entries.firstOrNull { it.value == type }?.key) {
                "unknown call signal type: $type"
            },
            callId = callId,
            sdp = sdp,
            sdpType = sdpType,
            video = video,
            candidate = candidate?.let { IceCandidatePayload(it.candidate, it.sdpMid, it.sdpMLineIndex) },
            reason = reason,
        )

        private val CALL_TYPES = mapOf(
            EnvelopeType.CALL_OFFER to CallSignalType.OFFER,
            EnvelopeType.CALL_RINGING to CallSignalType.RINGING,
            EnvelopeType.CALL_ANSWER to CallSignalType.ANSWER,
            EnvelopeType.CALL_ICE to CallSignalType.ICE,
            EnvelopeType.CALL_RENEGOTIATE to CallSignalType.RENEGOTIATE,
            EnvelopeType.CALL_RENEGOTIATE_ANSWER to CallSignalType.RENEGOTIATE_ANSWER,
            EnvelopeType.CALL_CAM to CallSignalType.CAM,
            EnvelopeType.CALL_REJECT to CallSignalType.REJECT,
            EnvelopeType.CALL_END to CallSignalType.END,
        )
    }
}

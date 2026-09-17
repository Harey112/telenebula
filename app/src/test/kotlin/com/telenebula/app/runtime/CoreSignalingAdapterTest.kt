package com.telenebula.app.runtime

import com.telenebula.calls.InboundSignal
import com.telenebula.core.model.Envelope
import com.telenebula.core.model.EnvelopeFrom
import com.telenebula.core.model.EnvelopeType
import com.telenebula.core.model.IceCandidatePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreSignalingAdapterTest {
    private fun envelope(type: EnvelopeType, callId: String? = "c1", sdp: String? = null, candidate: IceCandidatePayload? = null) =
        Envelope(v = 1, type = type, id = "e1", from = EnvelopeFrom("fd::1", "alice"), ts = 1, callId = callId, sdp = sdp, sdpType = "offer", candidate = candidate)

    @Test
    fun `every call type maps and nothing else does`() {
        assertTrue(CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.CALL_OFFER, sdp = "v=0")) is InboundSignal.Offer)
        assertTrue(CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.CALL_RINGING)) is InboundSignal.Ringing)
        assertTrue(CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.CALL_ANSWER, sdp = "v=0")) is InboundSignal.Answer)
        assertTrue(CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.CALL_RENEGOTIATE, sdp = "v=0")) is InboundSignal.Renegotiate)
        assertTrue(CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.CALL_RENEGOTIATE_ANSWER, sdp = "v=0")) is InboundSignal.RenegotiateAnswer)
        assertTrue(CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.CALL_CAM)) is InboundSignal.Cam)
        assertTrue(CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.CALL_REJECT)) is InboundSignal.Reject)
        assertTrue(CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.CALL_END)) is InboundSignal.End)
        assertNull(CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.MSG)))
        assertNull(CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.TYPING)))
    }

    @Test
    fun `a malformed frame is dropped at the boundary`() {
        assertNull("offer without sdp", CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.CALL_OFFER)))
        assertNull("answer without sdp", CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.CALL_ANSWER)))
        assertNull("no call id", CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.CALL_END, callId = null)))
        assertNull("empty call id", CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.CALL_END, callId = "")))
    }

    @Test
    fun `an ice frame without a candidate is the end-of-candidates marker`() {
        val marker = CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.CALL_ICE)) as InboundSignal.Ice
        assertNull(marker.candidate)
        val real = CoreSignalingAdapter.toInboundSignal(envelope(EnvelopeType.CALL_ICE, candidate = IceCandidatePayload("candidate:1", "0", 0))) as InboundSignal.Ice
        assertEquals("candidate:1", real.candidate?.candidate)
    }
}

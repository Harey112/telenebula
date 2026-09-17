package com.telenebula.calls

import com.telenebula.calls.media.WebRtcSessionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/** The callee's signaling sequence with fakes behind every seam; no libwebrtc, no Android. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallEngineTest {
    private class FakeSignaling : CoreSignaling {
        val sent = ArrayList<Pair<String, OutboundCallSignal>>()
        val logged = ArrayList<CallLogEntry>()
        var pingResult = 5L

        override suspend fun sendSignal(peerIp: String, signal: OutboundCallSignal, timeoutMs: Int) {
            sent += peerIp to signal
        }

        override suspend fun pingPeer(peerIp: String, timeoutMs: Int): Long = pingResult
        override suspend fun contact(ip: String): CallContact = CallContact("Harey", isBlocked = false, callsAllowed = true)
        override suspend fun ensureContact(ip: String, name: String) = Unit
        override suspend fun logCall(entry: CallLogEntry) {
            logged += entry
        }

        override fun normalizeIp(ip: String): String = ip.lowercase()
    }

    private class FakeAudio : CallAudioPort {
        override fun start(speaker: Boolean) = Unit
        override fun setSpeaker(on: Boolean) = Unit
        override fun startRingback() = Unit
        override fun stopRingback() = Unit
        override fun stop(playBusyTone: Boolean) = Unit
        override fun setProximityEnabled(enabled: Boolean) = Unit
    }

    private class FakeSystem : CallSystem {
        override fun startSession(peerName: String, video: Boolean, connectedAt: Long) = Unit
        override fun stopSession() = Unit
        override fun showIncoming(peerName: String, video: Boolean, vibrate: Boolean) = Unit
        override fun hideIncoming() = Unit
        override fun hideFloating() = Unit
        override fun showMissed(peerName: String, video: Boolean) = Unit
    }

    private class FakeTracks : LocalTracks {
        var releases = 0
        override val videoTrack: VideoTrack? = null
        override fun startCamera(): VideoTrack = throw IllegalStateException("no camera in tests")
        override fun stopCamera() = Unit
        override var isFrontCamera: Boolean = true
        override fun switchCamera(onSwitched: (Boolean) -> Unit) {
            isFrontCamera = !isFrontCamera
            onSwitched(isFrontCamera)
        }
        override fun setMicrophoneEnabled(enabled: Boolean) = Unit
        override fun release() {
            releases += 1
        }
    }

    private class FakeLink : PeerLink {
        val remoteCandidates = ArrayList<IceCandidate>()
        var isClosed = false
        override val isStable: Boolean = true
        override suspend fun createOffer() = SessionDescription(SessionDescription.Type.OFFER, "v=0 offer")
        override suspend fun createAnswer() = SessionDescription(SessionDescription.Type.ANSWER, "v=0 answer")
        override suspend fun setRemote(description: SessionDescription) = Unit
        override fun addRemoteCandidate(candidate: IceCandidate) {
            remoteCandidates += candidate
        }

        override fun setVideoTrack(track: VideoTrack?): Boolean = false
        override fun restartIce() = Unit
        override fun describeSelectedPair(onResult: (String) -> Unit) = onResult("fake pair")
        override fun close() {
            isClosed = true
        }
    }

    private class FakeMedia : CallMediaFactory {
        val links = ArrayList<FakeLink>()
        val tracks = ArrayList<FakeTracks>()
        var listener: WebRtcSessionListener? = null
        override val eglContext: EglBase.Context get() = throw IllegalStateException("no EGL in tests")
        override fun openLocal(video: Boolean): LocalTracks = FakeTracks().also { tracks += it }
        override fun openLink(listener: WebRtcSessionListener, local: LocalTracks): PeerLink {
            this.listener = listener
            return FakeLink().also { links += it }
        }

        override fun setVerbose(enabled: Boolean) = Unit
    }

    private class Harness(testScope: TestScope) {
        val core = FakeSignaling()
        val media = FakeMedia()
        val diagnostics = CallDiagnostics(isVerbose = { false }, now = { testScope.testScheduler.currentTime })
        val engine = CallEngine(
            core = core,
            prefs = { CallPrefs(ringForCalls = true, vibrateWhileRinging = false, missedNotification = false, videoSpeakerDefault = false) },
            audio = FakeAudio(),
            media = media,
            system = FakeSystem(),
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScope.testScheduler)),
            diag = diagnostics,
            workDispatcher = StandardTestDispatcher(testScope.testScheduler),
            now = { testScope.testScheduler.currentTime },
        )

        val link: FakeLink get() = media.links.single()
        val phase: CallPhase? get() = (engine.state.value as? CallState.Live)?.phase
        val session: CallSession? get() = (engine.state.value as? CallState.Live)?.session
        fun sentOfType(type: String): List<OutboundCallSignal> = core.sent.map { it.second }.filter { it.type == type }

        fun offer(callId: String = CALL_ID, sdp: String = "v=0 remote offer") =
            engine.handleSignal(PEER, InboundSignal.Offer(callId, "Harey", sdp, "offer", video = false))

        fun ice(sdp: String, callId: String = CALL_ID, from: String = PEER) =
            engine.handleSignal(from, InboundSignal.Ice(callId, IceCandidatePayload(sdp, sdpMid = "0", sdpMLineIndex = 0)))

        fun signal(signal: InboundSignal, from: String = PEER) = engine.handleSignal(from, signal)

        fun answer(callId: String = CALL_ID, from: String = PEER) = engine.handleSignal(from, InboundSignal.Answer(callId, "v=0 remote answer", "answer"))
    }

    @Test
    fun switchingCamera_reportsTheNewFacingToTheSession() = runTest {
        val h = Harness(this)
        h.offer()
        h.engine.accept()
        runCurrent()
        assertEquals(true, h.session?.isFrontCamera)

        h.engine.switchCamera()
        runCurrent()
        // the self-view mirrors off this: the back camera must read the right way round
        assertEquals(false, h.session?.isFrontCamera)

        h.engine.switchCamera()
        runCurrent()
        assertEquals(true, h.session?.isFrontCamera)
    }

    @Test
    fun candidatesBeforeAccept_areHeldAndHandedToTheSessionInOrder() = runTest {
        val h = Harness(this)
        h.offer()
        h.ice(CANDIDATE_A)
        h.ice(CANDIDATE_B)
        advanceUntilIdle()
        assertEquals(CallPhase.INCOMING, h.phase)
        assertTrue(h.media.links.isEmpty())

        h.engine.accept()
        runCurrent()
        assertEquals(CallPhase.CONNECTING, h.phase)
        assertEquals(listOf(CANDIDATE_A, CANDIDATE_B), h.link.remoteCandidates.map { it.sdp })
        assertEquals(1, h.sentOfType(CallSignalType.ANSWER).size)
    }

    @Test
    fun candidatesForAnotherCall_areDropped() = runTest {
        val h = Harness(this)
        h.offer()
        h.ice(CANDIDATE_A, callId = "someone-else")
        h.ice(CANDIDATE_B)
        h.engine.accept()
        runCurrent()
        assertEquals(listOf(CANDIDATE_B), h.link.remoteCandidates.map { it.sdp })
    }

    @Test
    fun reject_clearsTheHeldCandidates() = runTest {
        val h = Harness(this)
        h.offer()
        h.ice(CANDIDATE_A)
        advanceUntilIdle()
        h.engine.reject()
        advanceUntilIdle()
        assertTrue(h.engine.state.value is CallState.Ended)

        h.engine.acknowledgeEnded()
        h.offer(callId = "second")
        h.engine.accept()
        runCurrent()
        assertTrue(h.link.remoteCandidates.isEmpty())
    }

    @Test
    fun connecting_thatNeverComesUp_endsWithAReasonAndTellsThePeer() = runTest {
        val h = Harness(this)
        h.offer()
        h.engine.accept()
        runCurrent()
        assertEquals(CallPhase.CONNECTING, h.phase)

        advanceTimeBy(CallEngine.CONNECT_TIMEOUT_MS + 1)
        runCurrent()
        val ended = h.engine.state.value as? CallState.Ended
        assertNotNull(ended)
        assertEquals(CallEngine.CONNECT_FAILED_MESSAGE, ended?.reason)
        assertEquals(1, h.sentOfType(CallSignalType.END).size)
        assertTrue(h.link.isClosed)
        assertEquals(CallOutcome.FAILED, h.core.logged.single().outcome)
    }

    @Test
    fun connected_cancelsTheWatchdog() = runTest {
        val h = Harness(this)
        h.offer()
        h.engine.accept()
        runCurrent()
        h.media.listener?.onConnected()
        runCurrent()
        assertEquals(CallPhase.ACTIVE, h.phase)

        advanceTimeBy(CallEngine.CONNECT_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(CallPhase.ACTIVE, h.phase)
        assertTrue(h.sentOfType(CallSignalType.END).isEmpty())
    }

    @Test
    fun gatheringComplete_sendsAnIceFrameWithNoCandidate() = runTest {
        val h = Harness(this)
        h.offer()
        h.engine.accept()
        runCurrent()
        h.media.listener?.onLocalGatheringComplete()
        runCurrent()
        // a call-ice frame carrying no candidate is the end-of-candidates marker
        assertEquals(null, h.sentOfType(CallSignalType.ICE).single().candidate)
    }

    @Test
    fun iceFailure_onTheCallee_waitsThenGivesUp() = runTest {
        val h = Harness(this)
        h.offer()
        h.engine.accept()
        runCurrent()
        h.media.listener?.onIceFailed()
        runCurrent()
        assertEquals(CallPhase.CONNECTING, h.phase)

        advanceTimeBy(CallEngine.ICE_RESTART_GRACE_MS + 1)
        runCurrent()
        assertTrue(h.engine.state.value is CallState.Ended)
        assertEquals(1, h.sentOfType(CallSignalType.END).size)
    }

    @Test
    fun signalsFromAnotherAddress_areIgnored() = runTest {
        val h = Harness(this)
        h.offer()
        runCurrent()
        assertEquals(CallPhase.INCOMING, h.phase)

        h.signal(InboundSignal.End(CALL_ID), from = STRANGER)
        h.signal(InboundSignal.Reject(CALL_ID, null), from = STRANGER)
        h.ice(CANDIDATE_A, from = STRANGER)
        runCurrent()
        assertEquals("a stranger cannot end the call", CallPhase.INCOMING, h.phase)

        h.engine.accept()
        runCurrent()
        assertTrue("a stranger's candidate is never handed to the session", h.link.remoteCandidates.isEmpty())
        h.signal(InboundSignal.Cam(CALL_ID, isOn = true), from = STRANGER)
        runCurrent()
        assertEquals(false, h.session?.remoteCamOn)

        h.signal(InboundSignal.End(CALL_ID))
        runCurrent()
        assertTrue("the peer itself still can", h.engine.state.value is CallState.Ended)
    }

    @Test
    fun hangupOrReject_withNoCall_changesNothing() = runTest {
        val h = Harness(this)
        h.engine.hangup()
        h.engine.reject()
        advanceUntilIdle()
        assertEquals(CallState.Idle, h.engine.state.value)
        assertTrue(h.core.sent.isEmpty())
        assertTrue(h.core.logged.isEmpty())
    }

    @Test
    fun candidatesThatAreNotHost_areRefused() = runTest {
        val h = Harness(this)
        h.offer()
        h.ice("candidate:3 1 udp 1 203.0.113.9 3478 typ relay raddr 0.0.0.0 rport 0")
        h.ice("candidate:4 1 udp 1 203.0.113.9 3478 typ srflx raddr 0.0.0.0 rport 0")
        h.ice(CANDIDATE_A)
        h.engine.accept()
        runCurrent()
        assertEquals(listOf(CANDIDATE_A), h.link.remoteCandidates.map { it.sdp })
    }

    @Test
    fun anOversizedOffer_isIgnored() = runTest {
        val h = Harness(this)
        h.offer(sdp = "v".repeat(CallEngine.MAX_SDP_CHARS + 1))
        advanceUntilIdle()
        assertEquals(CallState.Idle, h.engine.state.value)
    }

    @Test
    fun outgoing_pingsThenOffers_andRingsOnTheAck() = runTest {
        val h = Harness(this)
        assertTrue(h.engine.startCall(PEER, video = false))
        runCurrent()
        assertEquals(CallPhase.CONTACTING, h.phase)
        assertEquals(1, h.sentOfType(CallSignalType.OFFER).size)

        h.signal(InboundSignal.Ringing(h.session?.callId ?: ""))
        runCurrent()
        assertEquals(CallPhase.RINGING, h.phase)
        assertEquals("no second call while one is live", false, h.engine.startCall(PEER, video = false))
    }

    @Test
    fun outgoing_toAnUnreachablePeer_endsWithTheUnreachableMessage() = runTest {
        val h = Harness(this)
        h.core.pingResult = -1
        h.engine.startCall(PEER, video = false)
        advanceTimeBy(CallEngine.PING_WINDOW_MS + 1_000)
        runCurrent()
        val ended = h.engine.state.value as? CallState.Ended
        assertEquals(CallEngine.UNREACHABLE_MESSAGE, ended?.reason)
        assertEquals(CallOutcome.UNREACHABLE, h.core.logged.single().outcome)
        assertTrue("the offer never went out to a peer that did not answer the probe", h.sentOfType(CallSignalType.OFFER).isEmpty())
        assertEquals(1, h.media.tracks.single().releases)
    }

    @Test
    fun outgoing_localCandidatesWaitForTheRingingAck_thenLeaveInOrder() = runTest {
        val h = Harness(this)
        h.engine.startCall(PEER, video = false)
        runCurrent()
        val callId = h.session?.callId ?: ""
        h.media.listener?.onLocalCandidate(IceCandidate("0", 0, CANDIDATE_A))
        h.media.listener?.onLocalCandidate(IceCandidate("0", 0, CANDIDATE_B))
        h.media.listener?.onLocalGatheringComplete()
        runCurrent()
        assertTrue("held while contacting", h.sentOfType(CallSignalType.ICE).isEmpty())

        h.signal(InboundSignal.Ringing(callId))
        runCurrent()
        val ice = h.sentOfType(CallSignalType.ICE)
        assertEquals(listOf(CANDIDATE_A, CANDIDATE_B, null), ice.map { it.candidate?.candidate })
    }

    @Test
    fun outgoing_noAnswer_endsAfterTheTimeoutAndTellsThePeer() = runTest {
        val h = Harness(this)
        h.engine.startCall(PEER, video = false)
        runCurrent()
        h.signal(InboundSignal.Ringing(h.session?.callId ?: ""))
        runCurrent()

        advanceTimeBy(CallEngine.NO_ANSWER_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals("No answer.", (h.engine.state.value as? CallState.Ended)?.reason)
        assertEquals(1, h.sentOfType(CallSignalType.END).size)
        assertEquals(CallOutcome.NO_ANSWER, h.core.logged.single().outcome)
    }

    @Test
    fun outgoing_answered_connectsAndTheLinkComingUpMakesItActive() = runTest {
        val h = Harness(this)
        h.engine.startCall(PEER, video = false)
        runCurrent()
        h.answer(h.session?.callId ?: "")
        runCurrent()
        assertEquals(CallPhase.CONNECTING, h.phase)
        h.media.listener?.onConnected()
        runCurrent()
        assertEquals(CallPhase.ACTIVE, h.phase)

        h.engine.hangup()
        runCurrent()
        assertEquals(CallOutcome.ANSWERED, h.core.logged.single().outcome)
        assertEquals(1, h.media.tracks.single().releases)
        assertTrue(h.link.isClosed)
    }

    @Test
    fun hangup_isIdempotent_oneEndSignalOneLogRow() = runTest {
        val h = Harness(this)
        h.offer()
        h.engine.accept()
        runCurrent()
        h.engine.hangup()
        h.engine.hangup()
        runCurrent()
        assertEquals(1, h.sentOfType(CallSignalType.END).size)
        assertEquals(1, h.core.logged.size)
        assertEquals(1, h.media.tracks.single().releases)
    }

    @Test
    fun aSecondOffer_whileLive_isRejectedBusy_andTheSameOfferIsReAcked() = runTest {
        val h = Harness(this)
        h.offer()
        runCurrent()
        h.offer(callId = "another")
        h.offer()
        runCurrent()
        val rejects = h.sentOfType(CallSignalType.REJECT)
        assertEquals(listOf("busy"), rejects.map { it.reason })
        assertEquals("another", rejects.single().callId)
        assertEquals(2, h.sentOfType(CallSignalType.RINGING).size)
    }

    @Test
    fun linkEvents_afterTheCallEnded_changeNothing() = runTest {
        val h = Harness(this)
        h.offer()
        h.engine.accept()
        runCurrent()
        h.engine.hangup()
        runCurrent()
        val listener = h.media.listener
        listener?.onConnected()
        listener?.onIceFailed()
        listener?.onConnectionClosed()
        runCurrent()
        assertTrue(h.engine.state.value is CallState.Ended)
        assertEquals(1, h.sentOfType(CallSignalType.END).size)
    }

    @Test
    fun close_endsTheCall_andStopsTheEngine() = runTest {
        val h = Harness(this)
        h.offer()
        h.engine.accept()
        runCurrent()
        h.engine.close()
        runCurrent()
        assertTrue(h.engine.state.value is CallState.Ended)
        assertEquals(1, h.media.tracks.single().releases)
    }

    private companion object {
        const val PEER = "fd10:100::107"
        const val STRANGER = "fd10:100::999"
        const val CALL_ID = "call-1"
        const val CANDIDATE_A = "candidate:1 1 udp 2130706431 fd10:100::107 50000 typ host"
        const val CANDIDATE_B = "candidate:2 1 udp 2130706431 192.168.1.20 50001 typ host"
    }
}

package com.telenebula.calls

import android.content.Context
import com.telenebula.calls.audio.CallAudio
import com.telenebula.calls.media.MediaUnavailableException
import com.telenebula.calls.media.WebRtcMediaFactory
import com.telenebula.calls.media.WebRtcRuntime
import com.telenebula.calls.media.WebRtcSessionListener
import com.telenebula.calls.media.sdpType
import com.telenebula.calls.media.wire
import com.telenebula.calls.system.AndroidCallSystem
import com.telenebula.calls.system.CallActionBus
import java.util.UUID
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * P2P audio/video calls over the nebula overlay. Signaling rides the core's TCP channel;
 * media is WebRTC with no STUN/TURN, so across networks the only pair that can carry media is the
 * two tun addresses. An audio call is a call that starts with the camera off; the first camera
 * enable adds the video m-line and renegotiates, later toggles swap the track.
 *
 * The call is one [Machine] value, replaced whole on every transition; every field a state does
 * not need does not exist in it. Every transition happens on [scope]: libwebrtc and core callbacks
 * hop through it first. The call sequence, timings and user-facing strings are those of the
 * original `callEngine.ts`.
 */
class CallEngine internal constructor(
    private val core: CoreSignaling,
    private val prefs: () -> CallPrefs,
    private val audio: CallAudioPort,
    private val media: CallMediaFactory,
    private val system: CallSystem,
    private val scope: CoroutineScope,
    private val diag: CallDiagnostics,
    /** libwebrtc opens and disposes media on blocking calls; they run here, never on the main thread */
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        context: Context,
        core: CoreSignaling,
        prefs: () -> CallPrefs,
        audio: CallAudio,
        runtime: WebRtcRuntime,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    ) : this(core, prefs, audio, WebRtcMediaFactory(context, runtime), AndroidCallSystem(context), scope, CallDiagnostics({ prefs().isVerboseLogging }))

    private val mutableState = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = mutableState.asStateFlow()

    private val openFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** a notification or the floating window asked for the call screen */
    val openRequests: SharedFlow<Unit> = openFlow.asSharedFlow()

    private val warningFlow = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** degraded-but-continuing conditions for the app's notice layer */
    val warnings: SharedFlow<String> = warningFlow.asSharedFlow()

    /** the last call's connection trail, for the Diagnostics screen */
    val diagnostics: StateFlow<List<String>> = diag.trail

    val eglContext: EglBase.Context get() = media.eglContext

    // --- the machine --------------------------------------------------------------------------

    /** One call attempt: what never changes while it lasts, and the timers that belong to it. */
    private class Attempt(
        val callId: String,
        val peer: CallPeer,
        val isIncoming: Boolean,
        val video: Boolean,
        val startedAt: Long,
        /** the parent of every timer and collector of this attempt, so ending it is one cancel */
        val jobs: CompletableJob,
    ) {
        var noAnswer: Job? = null
        var watchdog: Job? = null
        var hasRestartedIce = false
    }

    private class PendingOffer(val sdp: String, val sdpType: String?, val video: Boolean)

    /** The media of a call once a peer connection exists; the ICE sender is the one consumer of [iceOut]. */
    private class Media(val tracks: LocalTracks, val link: PeerLink, val iceOut: Channel<OutboundCallSignal>) {
        var iceSender: Job? = null
    }

    private sealed interface Machine {
        object Idle : Machine

        /** Published as [CallState.Ended] until acknowledged; [disposal] is the media release still running off the main thread. */
        class Ending(val ended: CallState.Ended, val disposal: Job?) : Machine

        sealed class Live(val attempt: Attempt, val session: CallSession) : Machine {
            abstract val phase: CallPhase
            open val media: Media? get() = null
            open val connectedAt: Long get() = 0
            abstract fun withSession(session: CallSession): Live
        }

        class Incoming(attempt: Attempt, session: CallSession, val offer: PendingOffer, val heldRemote: List<IceCandidatePayload>) : Live(attempt, session) {
            override val phase get() = CallPhase.INCOMING
            override fun withSession(session: CallSession) = Incoming(attempt, session, offer, heldRemote)
        }

        /** Outgoing, before the peer acknowledged the offer; [media] is null until the local tracks are open. */
        class Dialing(attempt: Attempt, session: CallSession, override val media: Media?) : Live(attempt, session) {
            override val phase get() = CallPhase.CONTACTING
            override fun withSession(session: CallSession) = Dialing(attempt, session, media)
        }

        class Ringing(attempt: Attempt, session: CallSession, override val media: Media) : Live(attempt, session) {
            override val phase get() = CallPhase.RINGING
            override fun withSession(session: CallSession) = Ringing(attempt, session, media)
        }

        class Connecting(attempt: Attempt, session: CallSession, override val media: Media) : Live(attempt, session) {
            override val phase get() = CallPhase.CONNECTING
            override fun withSession(session: CallSession) = Connecting(attempt, session, media)
        }

        class Active(attempt: Attempt, session: CallSession, override val media: Media, override val connectedAt: Long, val isLinkUp: Boolean) : Live(attempt, session) {
            override val phase get() = CallPhase.ACTIVE
            override fun withSession(session: CallSession) = Active(attempt, session, media, connectedAt, isLinkUp)
            fun withLink(isUp: Boolean) = Active(attempt, session, media, connectedAt, isUp)
        }
    }

    private var machine: Machine = Machine.Idle

    private val live: Machine.Live? get() = machine as? Machine.Live

    /** The live state, only while it is still this attempt's. */
    private fun current(attempt: Attempt): Machine.Live? = live?.takeIf { it.attempt === attempt }

    private fun transition(next: Machine) {
        machine = next
        mutableState.value = when (next) {
            Machine.Idle -> CallState.Idle
            is Machine.Ending -> next.ended
            is Machine.Live -> CallState.Live(next.phase, next.session, next.connectedAt)
        }
    }

    private fun newAttempt(callId: String, peer: CallPeer, isIncoming: Boolean, video: Boolean) =
        Attempt(callId, peer, isIncoming, video, now(), SupervisorJob(scope.coroutineContext[Job]))

    init {
        scope.launch { CallActionBus.actions.collect { onCallAction(it) } }
    }

    // --- public API -------------------------------------------------------------------------

    /**
     * Places a call. Returns false when a call is in progress or the contact is blocked; every
     * later outcome (ringing, unreachable, declined) is reported through [state].
     */
    suspend fun startCall(peerIp: String, video: Boolean): Boolean {
        if (machine is Machine.Live) return false
        val contact = core.contact(peerIp)
        if (contact?.isBlocked == true) return false
        if (machine is Machine.Live) return false
        // the previous call's camera may still be releasing; opening it again would fail
        (machine as? Machine.Ending)?.disposal?.join()

        val attempt = newAttempt(UUID.randomUUID().toString(), CallPeer(peerIp, contact?.label ?: peerIp), isIncoming = false, video = video)
        media.setVerbose(prefs().isVerboseLogging)
        diag.begin("outgoing", attempt.callId, peerIp)
        val speaker = video && prefs().videoSpeakerDefault
        transition(Machine.Dialing(attempt, CallSession(callId = attempt.callId, peer = attempt.peer, video = video, speaker = speaker), media = null))
        system.startSession(attempt.peer.name, video, 0)
        scope.launch(attempt.jobs) { runOutgoing(attempt, speaker) }
        return true
    }

    fun accept() {
        scope.launch { acceptCall() }
    }

    /** Local teardown first; the reject signal goes out in the background with a short timeout. */
    fun reject() {
        val incoming = live as? Machine.Incoming ?: return
        end(null, CallOutcome.DECLINED, playBusyTone = false)
        sendQuietly(incoming.attempt.peer.ip, OutboundCallSignal(CallSignalType.REJECT, incoming.attempt.callId, reason = "declined"), SHORT_TIMEOUT_MS)
    }

    /** Ends the call immediately; the call-end signal is delivered in the background. Idempotent. */
    fun hangup() {
        val current = live ?: return
        val outcome = when {
            current is Machine.Active -> null
            current.attempt.isIncoming -> CallOutcome.DECLINED
            else -> CallOutcome.CANCELLED
        }
        end(null, outcome, playBusyTone = false)
        sendQuietly(current.attempt.peer.ip, OutboundCallSignal(CallSignalType.END, current.attempt.callId), SHORT_TIMEOUT_MS)
    }

    fun toggleMute() {
        val current = live ?: return
        val muted = !current.session.muted
        current.media?.tracks?.setMicrophoneEnabled(!muted)
        updateSession { it.copy(muted = muted) }
    }

    fun toggleSpeaker() {
        val current = live ?: return
        val next = !current.session.speaker
        audio.setSpeaker(next)
        updateSession { it.copy(speaker = next) }
    }

    fun switchCamera() {
        // the camera thread answers, so hop before touching state
        live?.media?.tracks?.switchCamera { isFront -> scope.launch { updateSession { it.copy(isFrontCamera = isFront) } } }
    }

    /** Off releases the camera fully; the first enable of an audio call adds the m-line and renegotiates. */
    fun toggleCamera() {
        scope.launch { toggleCameraNow() }
    }

    /** Leaves the ended screen. */
    fun acknowledgeEnded() {
        if (machine is Machine.Ending) transition(Machine.Idle)
    }

    /** Inbound signal from the core, applied on the engine's own scope. */
    fun handleSignal(fromIp: String, signal: InboundSignal) {
        scope.launch { dispatch(signal, fromIp) }
    }

    fun onCallAction(action: CallAction) {
        when (action) {
            CallAction.ANSWER -> if (live is Machine.Incoming) accept()
            CallAction.DECLINE -> if (live is Machine.Incoming) reject()
            CallAction.HANGUP -> hangup()
            CallAction.OPEN -> openFlow.tryEmit(Unit)
        }
    }

    /** Ends whatever is live and stops listening; nothing this engine started outlives it. */
    fun close() {
        hangup()
        val disposal = (machine as? Machine.Ending)?.disposal
        // the media release runs on this scope; cancelling before it ran would leave the camera open
        scope.launch {
            disposal?.join()
            scope.cancel()
        }
    }

    // --- outbound ---------------------------------------------------------------------------

    private suspend fun runOutgoing(attempt: Attempt, speaker: Boolean) {
        val tracks = try {
            withContext(workDispatcher) { media.openLocal(attempt.video) }
        } catch (e: Exception) {
            diag.note("local media unavailable: ${e.message}")
            if (current(attempt) != null) end(mediaUnavailableMessage(attempt.video, isAnswering = false), null, playBusyTone = false)
            return
        }
        val dialing = current(attempt) as? Machine.Dialing
        if (dialing == null) {
            withContext(workDispatcher) { tracks.release() }
            return
        }
        val link = openLink(attempt, tracks, heldRemote = emptyList())
        transition(Machine.Dialing(attempt, dialing.session.copy(localVideo = tracks.videoTrack, isFrontCamera = tracks.isFrontCamera), link))

        val offer: SessionDescription = try {
            link.link.createOffer()
        } catch (e: Exception) {
            diag.note("offer could not be created: ${e.message}")
            if (current(attempt) != null) end("Something went wrong starting the call. Please try again.", null, playBusyTone = false)
            return
        }
        if (current(attempt) == null) return

        audio.start(speaker)
        val offerSignal = OutboundCallSignal(CallSignalType.OFFER, attempt.callId, sdp = offer.description, sdpType = offer.type.wire, video = attempt.video)

        // phase 1: ping only. A frame queued into a zombie socket looks sent, so the offer waits for a pong.
        val pingDeadline = now() + PING_WINDOW_MS
        var isReachable = false
        while (current(attempt) is Machine.Dialing && now() < pingDeadline) {
            val rtt = core.pingPeer(attempt.peer.ip, CONTACT_ATTEMPT_TIMEOUT_MS)
            if (rtt >= 0) {
                diag.note("peer answered the probe in $rtt ms")
                isReachable = true
                break
            }
            delay(CONTACT_RETRY_DELAY_MS)
        }
        if (current(attempt) == null) return
        if (!isReachable) {
            diag.note("peer never answered the probe")
            end(UNREACHABLE_MESSAGE, CallOutcome.UNREACHABLE, playBusyTone = true)
            return
        }

        // phase 2: offer until the peer's call-ringing ack; duplicates are ignored by the callee
        val offerDeadline = maxOf(pingDeadline, now()) + OFFER_EXTRA_MS
        while (current(attempt) is Machine.Dialing && now() < offerDeadline) {
            runCatching { core.sendSignal(attempt.peer.ip, offerSignal, CONTACT_ATTEMPT_TIMEOUT_MS) }
                .onFailure { diag.note("offer not delivered: ${it.message}") }
            withTimeoutOrNull(RING_ACK_WAIT_MS) { mutableState.first { !isContacting(it, attempt.callId) } }
        }
        if (current(attempt) == null) return
        if (current(attempt) is Machine.Dialing) {
            diag.note("offer was never acknowledged")
            end(UNREACHABLE_MESSAGE, CallOutcome.UNREACHABLE, playBusyTone = true)
        }
    }

    private fun isContacting(s: CallState, callId: String): Boolean =
        s is CallState.Live && s.phase == CallPhase.CONTACTING && s.session.callId == callId

    private fun armNoAnswerTimer(attempt: Attempt) {
        attempt.noAnswer?.cancel()
        attempt.noAnswer = scope.launch(attempt.jobs) {
            delay(NO_ANSWER_TIMEOUT_MS)
            if (current(attempt) !is Machine.Ringing) return@launch
            sendQuietly(attempt.peer.ip, OutboundCallSignal(CallSignalType.END, attempt.callId), 0)
            end("No answer.", CallOutcome.NO_ANSWER, playBusyTone = true)
        }
    }

    /**
     * Ends a call that does not come up within [timeoutMs] of entering CONNECTING (or of an ICE
     * failure). Both sides arm it, and the peer is told, so neither phone sits on "Connecting".
     */
    private fun armLinkWatchdog(attempt: Attempt, timeoutMs: Long) {
        attempt.watchdog?.cancel()
        diag.note("link watchdog armed: ${timeoutMs / 1000} s")
        attempt.watchdog = scope.launch(attempt.jobs) {
            delay(timeoutMs)
            val state = current(attempt) ?: return@launch
            if (state is Machine.Active && state.isLinkUp) return@launch
            diag.note("link watchdog fired")
            val wasActive = state is Machine.Active
            abort(if (wasActive) "Connection lost." else CONNECT_FAILED_MESSAGE, if (wasActive) null else CallOutcome.FAILED)
        }
    }

    /** Own candidates leave in order through one coroutine; started once the peer has proven it is there. */
    private fun startIceSender(attempt: Attempt, media: Media) {
        if (media.iceSender != null) return
        media.iceSender = scope.launch(attempt.jobs) {
            for (signal in media.iceOut) {
                runCatching { core.sendSignal(attempt.peer.ip, signal, 0) }
                    .onFailure { diag.note("candidate not delivered: ${it.message}") }
            }
        }
    }

    // --- inbound ----------------------------------------------------------------------------

    private suspend fun dispatch(signal: InboundSignal, fromIp: String) {
        if ((signal.sdpOrNull?.length ?: 0) > MAX_SDP_CHARS) return
        if (signal is InboundSignal.Offer) {
            handleOffer(signal, fromIp)
            return
        }
        // everything after the offer is about the current call and must come from its peer; the
        // call id alone is not proof, it travels in every frame of the call
        val current = live ?: return
        if (signal.callId != current.attempt.callId) return
        if (!isFrom(fromIp, current.attempt.peer.ip)) return
        when (signal) {
            is InboundSignal.Offer -> Unit
            is InboundSignal.Ringing -> onRinging(current)
            is InboundSignal.Answer -> handleAnswer(signal, current)
            is InboundSignal.Ice -> onRemoteCandidate(signal.candidate, current)
            is InboundSignal.Renegotiate -> handleRenegotiate(signal, current)
            is InboundSignal.RenegotiateAnswer -> {
                val link = current.media?.link ?: return
                runCatching { link.setRemote(SessionDescription(sdpType(signal.sdpType, SessionDescription.Type.ANSWER), signal.sdp)) }
                    .onFailure { warningFlow.tryEmit("Couldn't switch the call's video: ${it.message}") }
            }
            is InboundSignal.Cam -> updateSession { it.copy(remoteCamOn = signal.isOn) }
            is InboundSignal.Reject -> end(if (signal.reason == "busy") "They are on another call right now." else "Call declined.", CallOutcome.DECLINED, playBusyTone = true)
            is InboundSignal.End -> {
                val wasIncoming = current is Machine.Incoming
                end(if (wasIncoming) "Missed call." else "Call ended.", if (wasIncoming) CallOutcome.MISSED else CallOutcome.CANCELLED, playBusyTone = false)
            }
        }
    }

    private fun isFrom(fromIp: String, peerIp: String): Boolean = core.normalizeIp(fromIp) == core.normalizeIp(peerIp)

    private fun onRinging(current: Machine.Live) {
        val dialing = current as? Machine.Dialing ?: return
        val media = dialing.media ?: return
        diag.note("peer is ringing")
        transition(Machine.Ringing(dialing.attempt, dialing.session, media))
        audio.startRingback()
        startIceSender(dialing.attempt, media)
        armNoAnswerTimer(dialing.attempt)
    }

    /**
     * Only what this app can produce: a host candidate of sane length. There is no STUN or TURN
     * on either side, so a relayed or reflexive candidate is an address a peer wants us to probe.
     */
    private fun isAcceptableCandidate(c: IceCandidatePayload): Boolean {
        if (c.candidate.length > MAX_CANDIDATE_CHARS) return false
        val parts = c.candidate.split(' ')
        val type = parts.indexOf("typ").takeIf { it >= 0 }?.let { parts.getOrNull(it + 1) }
        return type == "host"
    }

    /**
     * A candidate that arrives before this side has a session (the callee still rings) waits for
     * `accept`; dropping it would leave the callee with nothing to pair against the caller's
     * only cross-network route. A missing candidate is the caller's end-of-candidates marker.
     */
    private fun onRemoteCandidate(c: IceCandidatePayload?, current: Machine.Live) {
        if (c == null) {
            diag.note("remote: end of candidates")
            return
        }
        if (!isAcceptableCandidate(c)) {
            diag.note("remote candidate refused")
            return
        }
        val link = current.media?.link
        when {
            link != null -> {
                link.addRemoteCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex ?: 0, c.candidate))
                diag.note("remote candidate ${describe(c.candidate)}")
            }
            current is Machine.Incoming && current.heldRemote.size < MAX_BUFFERED_CANDIDATES -> {
                transition(Machine.Incoming(current.attempt, current.session, current.offer, current.heldRemote + c))
                diag.note("remote candidate held until accept (${current.heldRemote.size + 1}) ${describe(c.candidate)}")
            }
            else -> diag.note("remote candidate dropped: buffer full")
        }
    }

    private suspend fun handleOffer(signal: InboundSignal.Offer, fromIp: String) {
        val current = live
        // the caller re-offers until it hears call-ringing: ack the same call again, never busy-reject it
        if (current != null && current.attempt.callId == signal.callId) {
            if (current is Machine.Incoming && isFrom(fromIp, current.attempt.peer.ip)) {
                sendQuietly(current.attempt.peer.ip, OutboundCallSignal(CallSignalType.RINGING, signal.callId), SHORT_TIMEOUT_MS)
            }
            return
        }
        if (current != null) {
            sendQuietly(fromIp, OutboundCallSignal(CallSignalType.REJECT, signal.callId, reason = "busy"), 0)
            return
        }
        val settings = prefs()
        val gate = core.contact(fromIp)
        if (!settings.ringForCalls || gate?.callsAllowed == false) {
            sendQuietly(fromIp, OutboundCallSignal(CallSignalType.REJECT, signal.callId, reason = "declined"), SHORT_TIMEOUT_MS)
            val stamp = now()
            scope.launch { runCatching { core.logCall(CallLogEntry(signal.callId, fromIp, isIncoming = true, isVideo = signal.video, CallOutcome.DECLINED, stamp, null, stamp)) } }
            return
        }
        (machine as? Machine.Ending)?.disposal?.join()

        core.ensureContact(fromIp, signal.fromName)
        val label = core.contact(fromIp)?.label ?: signal.fromName.ifEmpty { fromIp }
        // a call may have started while the contact was being written
        if (live != null) return
        val attempt = newAttempt(signal.callId, CallPeer(fromIp, label), isIncoming = true, video = signal.video)
        media.setVerbose(settings.isVerboseLogging)
        diag.begin("incoming", attempt.callId, fromIp)
        transition(
            Machine.Incoming(
                attempt,
                CallSession(callId = attempt.callId, peer = attempt.peer, video = signal.video),
                PendingOffer(signal.sdp, signal.sdpType, signal.video),
                heldRemote = emptyList(),
            ),
        )

        // the SYSTEM rings through the CallStyle channel, so ringer mode and Do Not Disturb are Android's decision
        system.showIncoming(attempt.peer.name, signal.video, settings.vibrateWhileRinging)
        sendQuietly(fromIp, OutboundCallSignal(CallSignalType.RINGING, attempt.callId), SHORT_TIMEOUT_MS)
    }

    private suspend fun acceptCall() {
        val incoming = live as? Machine.Incoming ?: return
        val attempt = incoming.attempt
        val offer = incoming.offer
        system.hideIncoming()
        diag.note("accepted")
        try {
            val tracks = withContext(workDispatcher) { media.openLocal(offer.video) }
            val still = current(attempt) as? Machine.Incoming
            if (still == null) {
                withContext(workDispatcher) { tracks.release() }
                return
            }
            val link = openLink(attempt, tracks, still.heldRemote)
            val connecting = Machine.Connecting(attempt, still.session.copy(localVideo = tracks.videoTrack), link)
            transition(connecting)
            startIceSender(attempt, link)
            armLinkWatchdog(attempt, CONNECT_TIMEOUT_MS)
            link.link.setRemote(SessionDescription(sdpType(offer.sdpType, SessionDescription.Type.OFFER), offer.sdp))
            val answer = link.link.createAnswer()
            val speaker = offer.video && prefs().videoSpeakerDefault
            updateSession { it.copy(speaker = speaker) }
            audio.start(speaker)
            system.startSession(attempt.peer.name, offer.video, 0)
            core.sendSignal(
                attempt.peer.ip,
                OutboundCallSignal(CallSignalType.ANSWER, attempt.callId, sdp = answer.description, sdpType = answer.type.wire),
                ANSWER_TIMEOUT_MS,
            )
            diag.note("answer sent")
        } catch (e: Exception) {
            if (current(attempt) == null) return
            // the caller must hear that this side gave up, or it keeps ringing until its own timer
            val reason = if (e is MediaUnavailableException) mediaUnavailableMessage(offer.video, isAnswering = true) else "Could not answer: ${e.message}"
            abort(reason, CallOutcome.FAILED, playBusyTone = false)
        }
    }

    private suspend fun handleAnswer(signal: InboundSignal.Answer, current: Machine.Live) {
        // an answer belongs to a call this side placed and has media for
        if (current !is Machine.Dialing && current !is Machine.Ringing) return
        val media = current.media ?: return
        val attempt = current.attempt
        attempt.noAnswer?.cancel()
        attempt.noAnswer = null
        audio.stopRingback()
        diag.note("answer received")
        startIceSender(attempt, media)
        try {
            media.link.setRemote(SessionDescription(sdpType(signal.sdpType, SessionDescription.Type.ANSWER), signal.sdp))
            val now = current(attempt) ?: return
            transition(Machine.Connecting(attempt, now.session, media))
            armLinkWatchdog(attempt, CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            diag.note("answer rejected by libwebrtc: ${e.message}")
            abort("The call could not connect. Please try again.", CallOutcome.FAILED)
        }
    }

    private suspend fun handleRenegotiate(signal: InboundSignal.Renegotiate, current: Machine.Live) {
        val link = current.media?.link ?: return
        try {
            link.setRemote(SessionDescription(sdpType(signal.sdpType, SessionDescription.Type.OFFER), signal.sdp))
            val answer = link.createAnswer()
            core.sendSignal(
                current.attempt.peer.ip,
                OutboundCallSignal(CallSignalType.RENEGOTIATE_ANSWER, current.attempt.callId, sdp = answer.description, sdpType = answer.type.wire),
                ANSWER_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            warningFlow.tryEmit("Couldn't switch the call's video: ${e.message}")
        }
    }

    /** Sends a fresh offer: for a newly added video m-line, or with new ICE credentials after a restart. */
    private suspend fun renegotiate(current: Machine.Live) {
        val link = current.media?.link ?: return
        if (!link.isStable) return // glare — extremely unlikely 1:1
        val offer = link.createOffer()
        core.sendSignal(
            current.attempt.peer.ip,
            OutboundCallSignal(CallSignalType.RENEGOTIATE, current.attempt.callId, sdp = offer.description, sdpType = offer.type.wire),
            ANSWER_TIMEOUT_MS,
        )
    }

    private suspend fun toggleCameraNow() {
        val current = live ?: return
        val media = current.media ?: return
        val attempt = current.attempt
        if (!current.session.camOff) {
            media.tracks.stopCamera()
            runCatching { media.link.setVideoTrack(null) }
            updateSession { it.copy(camOff = true, localVideo = null) }
            sendQuietly(attempt.peer.ip, OutboundCallSignal(CallSignalType.CAM, attempt.callId, video = false), 0)
            return
        }
        val track: VideoTrack = try {
            media.tracks.startCamera()
        } catch (e: Exception) {
            warningFlow.tryEmit("Camera is not available: ${e.message}")
            return
        }
        val still = current(attempt)
        if (still == null) {
            media.tracks.stopCamera()
            return
        }
        try {
            if (media.link.setVideoTrack(track)) renegotiate(still)
        } catch (e: Exception) {
            warningFlow.tryEmit("Couldn't switch the call's video: ${e.message}")
        }
        updateSession { it.copy(camOff = false, localVideo = track, isFrontCamera = media.tracks.isFrontCamera) }
        sendQuietly(attempt.peer.ip, OutboundCallSignal(CallSignalType.CAM, attempt.callId, video = true), 0)
    }

    // --- the link -----------------------------------------------------------------------------

    private sealed interface LinkEvent {
        class LocalCandidate(val candidate: IceCandidate) : LinkEvent
        object GatheringComplete : LinkEvent
        class RemoteVideo(val track: VideoTrack) : LinkEvent
        object Connected : LinkEvent
        object IceFailed : LinkEvent
        object Closed : LinkEvent
        class State(val what: String) : LinkEvent
    }

    /**
     * Opens the peer connection. libwebrtc reports from its own threads; every report is queued
     * and handled by one collector of this attempt, so nothing is reordered and nothing outlives
     * the call.
     */
    private fun openLink(attempt: Attempt, tracks: LocalTracks, heldRemote: List<IceCandidatePayload>): Media {
        val events = Channel<LinkEvent>(capacity = LINK_EVENT_CAP)
        fun report(event: LinkEvent) {
            if (events.trySend(event).isFailure) diag.note("link event dropped: buffer full")
        }
        val link = media.openLink(
            object : WebRtcSessionListener {
                override fun onLocalCandidate(candidate: IceCandidate) = report(LinkEvent.LocalCandidate(candidate))
                override fun onLocalGatheringComplete() = report(LinkEvent.GatheringComplete)
                override fun onRemoteVideo(track: VideoTrack) = report(LinkEvent.RemoteVideo(track))
                override fun onConnected() = report(LinkEvent.Connected)
                override fun onIceFailed() = report(LinkEvent.IceFailed)
                override fun onConnectionClosed() = report(LinkEvent.Closed)
                override fun onLinkState(what: String) = report(LinkEvent.State(what))
            },
            tracks,
        )
        val media = Media(tracks, link, Channel(capacity = MAX_BUFFERED_CANDIDATES))
        scope.launch(attempt.jobs) {
            for (event in events) onLinkEvent(attempt, media, event)
        }
        if (heldRemote.isNotEmpty()) {
            for (c in heldRemote) link.addRemoteCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex ?: 0, c.candidate))
            diag.note("handed ${heldRemote.size} held remote candidates to the session")
        }
        return media
    }

    private suspend fun onLinkEvent(attempt: Attempt, media: Media, event: LinkEvent) {
        val current = current(attempt) ?: return
        when (event) {
            is LinkEvent.LocalCandidate -> {
                val payload = IceCandidatePayload(event.candidate.sdp, event.candidate.sdpMid, event.candidate.sdpMLineIndex)
                // queued in order; the sender starts once the peer is known to be there
                if (media.iceOut.trySend(OutboundCallSignal(CallSignalType.ICE, attempt.callId, candidate = payload)).isFailure) {
                    diag.note("local candidate dropped: buffer full")
                } else {
                    diag.note("local candidate ${describe(event.candidate.sdp)}")
                }
            }
            LinkEvent.GatheringComplete -> {
                diag.note("local: end of candidates")
                media.iceOut.trySend(OutboundCallSignal(CallSignalType.ICE, attempt.callId, candidate = null))
            }
            is LinkEvent.RemoteVideo -> updateSession { it.copy(remoteVideo = event.track) }
            LinkEvent.Connected -> activate(current)
            LinkEvent.IceFailed -> onLinkFailed(current)
            LinkEvent.Closed -> abort("Connection lost.", null)
            is LinkEvent.State -> diag.note(event.what)
        }
    }

    /**
     * libwebrtc gave up on every pair. The caller restarts ICE once (new credentials ride the
     * renegotiation offer, fresh candidates follow from continual gathering); the callee waits
     * for that restart. Either way the watchdog ends the call if the link does not come back.
     */
    private suspend fun onLinkFailed(current: Machine.Live) {
        val media = current.media ?: return
        val attempt = current.attempt
        if (current is Machine.Active && current.isLinkUp) transition(current.withLink(isUp = false))
        if (!attempt.isIncoming && !attempt.hasRestartedIce && media.link.isStable) {
            attempt.hasRestartedIce = true
            diag.note("ice failed: restarting")
            try {
                media.link.restartIce()
                current(attempt)?.let { renegotiate(it) }
            } catch (e: Exception) {
                diag.note("ice restart offer failed: ${e.message}")
            }
            armLinkWatchdog(attempt, CONNECT_TIMEOUT_MS)
        } else {
            diag.note("ice failed: waiting for the caller's restart")
            armLinkWatchdog(attempt, ICE_RESTART_GRACE_MS)
        }
    }

    private fun activate(current: Machine.Live) {
        val media = current.media ?: return
        val attempt = current.attempt
        attempt.watchdog?.cancel()
        attempt.watchdog = null
        media.link.describeSelectedPair { line -> scope.launch { if (current(attempt) != null) diag.note("media path: $line") } }
        if (current is Machine.Active) {
            diag.note("link is back")
            if (!current.isLinkUp) transition(current.withLink(isUp = true))
            return
        }
        diag.note("connected")
        attempt.noAnswer?.cancel()
        attempt.noAnswer = null
        audio.stopRingback()
        val connectedAt = now()
        transition(Machine.Active(attempt, current.session, media, connectedAt, isLinkUp = true))
        audio.setProximityEnabled(true)
        // re-anchor the ongoing notification's chronometer at the ANSWER moment
        system.startSession(attempt.peer.name, current.session.video, connectedAt)
    }

    // --- ending -------------------------------------------------------------------------------

    /** Ends the attempt from this side's own failure and tells the peer, so it never waits on "Connecting". */
    private fun abort(reason: String?, outcome: CallOutcome?, playBusyTone: Boolean = true) {
        val current = live ?: return
        end(reason, outcome, playBusyTone)
        sendQuietly(current.attempt.peer.ip, OutboundCallSignal(CallSignalType.END, current.attempt.callId), SHORT_TIMEOUT_MS)
    }

    /**
     * The one exit. Publishes [CallState.Ended] first so every renderer detaches its sink, then
     * releases the media off the main thread. Idempotent: a second call finds nothing live.
     */
    private fun end(reason: String?, outcome: CallOutcome?, playBusyTone: Boolean) {
        val current = live ?: return
        // first, so an insistent ringtone can never outlive anything below that might throw
        system.hideIncoming()
        diag.note("ended: ${reason ?: "hung up"}")
        recordCallLog(current, outcome)
        current.attempt.jobs.cancel()
        system.stopSession()
        system.hideFloating()
        audio.stop(playBusyTone)

        val ended = CallState.Ended(current.session.peer, current.session.video, reason)
        transition(Machine.Ending(ended, disposal = null))
        val media = current.media ?: return
        val disposal = scope.launch(workDispatcher) {
            media.iceOut.close()
            media.tracks.release()
            media.link.close()
        }
        machine = Machine.Ending(ended, disposal)
    }

    /** Writes one call log row for the attempt that is being torn down. */
    private fun recordCallLog(current: Machine.Live, outcome: CallOutcome?) {
        val attempt = current.attempt
        val connectedAt = (current as? Machine.Active)?.connectedAt
        val resolved = when {
            connectedAt != null -> CallOutcome.ANSWERED
            outcome != null -> outcome
            attempt.isIncoming -> CallOutcome.MISSED
            else -> CallOutcome.CANCELLED
        }
        val s = current.session
        val isVideo = s.video || !s.camOff || s.remoteCamOn
        if (resolved == CallOutcome.MISSED && prefs().missedNotification) system.showMissed(s.peer.name, isVideo)
        val entry = CallLogEntry(attempt.callId, attempt.peer.ip, attempt.isIncoming, isVideo, resolved, attempt.startedAt, connectedAt, now())
        scope.launch {
            runCatching { core.logCall(entry) }.onFailure { warningFlow.tryEmit("The call was not written to the call log: ${it.message}") }
        }
    }

    private fun sendQuietly(peerIp: String, signal: OutboundCallSignal, timeoutMs: Int) {
        scope.launch {
            runCatching { core.sendSignal(peerIp, signal, timeoutMs) }
                .onFailure { diag.note("${signal.type} not delivered: ${it.message}") }
        }
    }

    private inline fun updateSession(block: (CallSession) -> CallSession) {
        val current = live ?: return
        transition(current.withSession(block(current.session)))
    }

    private fun mediaUnavailableMessage(video: Boolean, isAnswering: Boolean): String = when {
        isAnswering && video -> "Could not answer. Check camera and microphone permissions, then try again."
        isAnswering -> "Could not answer. Check microphone permissions, then try again."
        video -> "Camera or microphone is not available. Check app permissions and try again."
        else -> "Microphone is not available. Check app permissions and try again."
    }

    /** `candidate:… <address> <port> typ <type>` → "type family [address]:port" for the trail. */
    private fun describe(candidateSdp: String): String {
        val parts = candidateSdp.split(' ')
        val address = parts.getOrNull(4) ?: return candidateSdp.take(40)
        val port = parts.getOrNull(5).orEmpty()
        val type = parts.indexOf("typ").takeIf { it >= 0 }?.let { parts.getOrNull(it + 1) } ?: "?"
        val family = when {
            address.startsWith("fd", ignoreCase = true) || address.startsWith("fc", ignoreCase = true) -> "overlay v6"
            address.contains(':') -> "v6"
            else -> "v4"
        }
        return "$type $family [$address]:$port"
    }

    internal companion object {
        /** Contacting phase 1: ping until the peer answers, at most this long. */
        const val PING_WINDOW_MS = 10_000L
        /** Contacting phase 2 budget = what is left of the ping window + this. */
        const val OFFER_EXTRA_MS = 5_000L
        /** Per ping / per offer send timeout (connect included) while contacting. */
        const val CONTACT_ATTEMPT_TIMEOUT_MS = 3_000
        /** Pause between failed pings. */
        const val CONTACT_RETRY_DELAY_MS = 700L
        /** How long one offer waits for the peer's call-ringing ack before it is re-sent. */
        const val RING_ACK_WAIT_MS = 2_000L
        /** Give up when the peer rings this long without answering. */
        const val NO_ANSWER_TIMEOUT_MS = 60_000L
        /** CONNECTING (or an ICE restart) that does not come up within this ends the call on both sides. */
        const val CONNECT_TIMEOUT_MS = 30_000L
        /** The callee's patience for the caller's ICE restart after a failure. */
        const val ICE_RESTART_GRACE_MS = 15_000L
        /** Candidates held per direction while the session or the peer is not there yet. */
        const val MAX_BUFFERED_CANDIDATES = 64
        /** libwebrtc reports queued for the engine; a burst past this drops the oldest, and the watchdog still ends a dead call. */
        const val LINK_EVENT_CAP = 128
        const val SHORT_TIMEOUT_MS = 3_000
        const val ANSWER_TIMEOUT_MS = 5_000
        /** An SDP for one audio and one video m-line is a few KiB; this is a bound on a peer, not a format limit. */
        const val MAX_SDP_CHARS = 64 * 1024
        const val MAX_CANDIDATE_CHARS = 512
        const val UNREACHABLE_MESSAGE = "Can't reach them right now. Ask them to open TeleNebula and try again."
        const val CONNECT_FAILED_MESSAGE = "Couldn't connect the call. Both phones need to reach the nebula network; try again."
    }
}

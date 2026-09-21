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
import kotlinx.coroutines.CompletableDeferred
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
 *
 * The media has a seat: this phone, or one Dex browser. A browser's peer connection is driven
 * through [RemoteSeatPort]; the phone relays its SDP and (rewritten) candidates to the peer as
 * if they were its own. Only the seat may move the call, and a move is an ICE restart offered
 * from the new seat's connection.
 */
class CallEngine internal constructor(
    private val core: CoreSignaling,
    private val prefs: () -> CallPrefs,
    private val audio: CallAudioPort,
    private val media: CallMediaFactory,
    private val system: CallSystem,
    private val remote: RemoteSeatPort,
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
        remote: RemoteSeatPort,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    ) : this(core, prefs, audio, WebRtcMediaFactory(context, runtime), AndroidCallSystem(context), remote, scope, CallDiagnostics({ prefs().isVerboseLogging }))

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

    /** the Dex browsers a call could be moved to */
    val remoteClients: StateFlow<List<RemoteClient>> get() = remote.clients

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
        /** the offer a Dex browser produces for a call it placed */
        val remoteOffer = CompletableDeferred<PendingOffer>()
    }

    private class PendingOffer(val sdp: String, val sdpType: String?, val video: Boolean)

    /** Own candidates for one connection leave in order through one sender, started once the peer is known to be there. */
    private class IceQueue {
        val out = Channel<OutboundCallSignal>(capacity = MAX_BUFFERED_CANDIDATES)
        var sender: Job? = null
    }

    /** The phone's media once a peer connection exists. */
    private class Media(val tracks: LocalTracks, val link: PeerLink, val ice: IceQueue) {
        var collector: Job? = null
    }

    private sealed interface Seat {
        val ice: IceQueue

        class Phone(val media: Media) : Seat {
            override val ice: IceQueue get() = media.ice
        }

        class Remote(val clientId: String, override val ice: IceQueue = IceQueue()) : Seat
    }

    /** A move of the media towards another seat; [deadline] ends it if the new seat never comes up. */
    private sealed interface Move {
        val deadline: Job

        class ToRemote(val clientId: String, val ice: IceQueue, override val deadline: Job) : Move
        class ToPhone(val media: Media, override val deadline: Job) : Move
    }

    private sealed interface Machine {
        object Idle : Machine

        /** Published as [CallState.Ended] until acknowledged; [disposal] is the media release still running off the main thread. */
        class Ending(val ended: CallState.Ended, val disposal: Job?) : Machine

        sealed class Live(val attempt: Attempt, val session: CallSession) : Machine {
            abstract val phase: CallPhase
            abstract val seat: Seat?
            open val connectedAt: Long get() = 0
            open val move: Move? get() = null
            val media: Media? get() = (seat as? Seat.Phone)?.media
            abstract fun withSession(session: CallSession): Live
        }

        class Incoming(attempt: Attempt, session: CallSession, val offer: PendingOffer, val heldRemote: List<IceCandidatePayload>) : Live(attempt, session) {
            override val phase get() = CallPhase.INCOMING
            override val seat: Seat? get() = null
            override fun withSession(session: CallSession) = Incoming(attempt, session, offer, heldRemote)
        }

        /** Outgoing, before the peer acknowledged the offer; a phone seat is null until the local tracks are open. */
        class Dialing(attempt: Attempt, session: CallSession, override val seat: Seat?) : Live(attempt, session) {
            override val phase get() = CallPhase.CONTACTING
            override fun withSession(session: CallSession) = Dialing(attempt, session, seat)
        }

        class Ringing(attempt: Attempt, session: CallSession, override val seat: Seat) : Live(attempt, session) {
            override val phase get() = CallPhase.RINGING
            override fun withSession(session: CallSession) = Ringing(attempt, session, seat)
        }

        class Connecting(attempt: Attempt, session: CallSession, override val seat: Seat) : Live(attempt, session) {
            override val phase get() = CallPhase.CONNECTING
            override fun withSession(session: CallSession) = Connecting(attempt, session, seat)
        }

        class Active(
            attempt: Attempt,
            session: CallSession,
            override val seat: Seat,
            override val connectedAt: Long,
            val isLinkUp: Boolean,
            override val move: Move? = null,
        ) : Live(attempt, session) {
            override val phase get() = CallPhase.ACTIVE
            override fun withSession(session: CallSession) = Active(attempt, session, seat, connectedAt, isLinkUp, move)
            fun withLink(isUp: Boolean) = Active(attempt, session, seat, connectedAt, isUp, move)
            fun withMove(move: Move?) = Active(attempt, session, seat, connectedAt, isLinkUp, move)
            fun withSeat(seat: Seat, session: CallSession) = Active(attempt, session, seat, connectedAt, isLinkUp = true, move = null)
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
            is Machine.Live -> CallState.Live(next.phase, next.session.copy(seat = publicSeat(next.seat), movingTo = publicMoveTarget(next.move)), next.connectedAt)
        }
    }

    private fun publicSeat(seat: Seat?): CallSeat = when (seat) {
        is Seat.Remote -> CallSeat.Remote(seat.clientId)
        is Seat.Phone, null -> CallSeat.Phone
    }

    private fun publicMoveTarget(move: Move?): CallSeat? = when (move) {
        is Move.ToRemote -> CallSeat.Remote(move.clientId)
        is Move.ToPhone -> CallSeat.Phone
        null -> null
    }

    private fun newAttempt(callId: String, peer: CallPeer, isIncoming: Boolean, video: Boolean) =
        Attempt(callId, peer, isIncoming, video, now(), SupervisorJob(scope.coroutineContext[Job]))

    private fun isKnownClient(clientId: String): Boolean = remote.clients.value.any { it.id == clientId }

    private fun isSeatOf(current: Machine.Live, clientId: String): Boolean = (current.seat as? Seat.Remote)?.clientId == clientId

    private fun moveTargetOf(current: Machine.Live): Move.ToRemote? = current.move as? Move.ToRemote

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
        transition(Machine.Dialing(attempt, CallSession(callId = attempt.callId, peer = attempt.peer, video = video, speaker = speaker), seat = null))
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

    /** Ends a call this phone holds; the call-end signal is delivered in the background. Idempotent; a Dex-seated call is left to Dex. */
    fun hangup() {
        val current = live ?: return
        if (current.seat is Seat.Remote) return
        hangupNow(current)
    }

    fun toggleMute() {
        val current = live ?: return
        val media = current.media ?: return
        val muted = !current.session.muted
        media.tracks.setMicrophoneEnabled(!muted)
        updateSession { it.copy(muted = muted) }
    }

    fun toggleSpeaker() {
        val current = live ?: return
        if (current.seat is Seat.Remote) return
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

    /** Hands an active phone call's media to one Dex browser; anything else is a no-op. */
    fun moveToRemote(clientId: String) {
        scope.launch { moveToRemoteNow(clientId) }
    }

    /** Leaves the ended screen. */
    fun acknowledgeEnded() {
        if (machine is Machine.Ending) transition(Machine.Idle)
    }

    /** Inbound signal from the core, applied on the engine's own scope. */
    fun handleSignal(fromIp: String, signal: InboundSignal) {
        scope.launch { dispatch(signal, fromIp) }
    }

    /** What a Dex browser asked for or reported, applied on the engine's own scope. */
    fun onRemote(command: RemoteCommand) {
        scope.launch { dispatchRemote(command) }
    }

    fun onCallAction(action: CallAction) {
        when (action) {
            CallAction.ANSWER -> if (live is Machine.Incoming) accept()
            CallAction.DECLINE -> if (live is Machine.Incoming) reject()
            CallAction.HANGUP -> hangup()
            CallAction.OPEN -> openFlow.tryEmit(Unit)
        }
    }

    /** Ends whatever is live, wherever its seat is, and stops listening; nothing this engine started outlives it. */
    fun close() {
        endAll()
        val disposal = (machine as? Machine.Ending)?.disposal
        // the media release runs on this scope; cancelling before it ran would leave the camera open
        scope.launch {
            disposal?.join()
            scope.cancel()
        }
    }

    private fun hangupNow(current: Machine.Live) {
        val outcome = when {
            current is Machine.Active -> null
            current.attempt.isIncoming -> CallOutcome.DECLINED
            else -> CallOutcome.CANCELLED
        }
        end(null, outcome, playBusyTone = false)
        sendQuietly(current.attempt.peer.ip, OutboundCallSignal(CallSignalType.END, current.attempt.callId), SHORT_TIMEOUT_MS)
    }

    private fun endAll() {
        val current = live ?: return
        hangupNow(current)
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
        if (dialing == null || dialing.seat != null) {
            withContext(workDispatcher) { tracks.release() }
            return
        }
        val link = openLink(attempt, tracks, heldRemote = emptyList())
        transition(Machine.Dialing(attempt, dialing.session.copy(localVideo = tracks.videoTrack, isFrontCamera = tracks.isFrontCamera), Seat.Phone(link)))

        val offer: SessionDescription = try {
            link.link.createOffer()
        } catch (e: Exception) {
            diag.note("offer could not be created: ${e.message}")
            if (current(attempt) != null) end("Something went wrong starting the call. Please try again.", null, playBusyTone = false)
            return
        }
        if (current(attempt) == null) return

        audio.start(speaker)
        contactPeer(attempt, OutboundCallSignal(CallSignalType.OFFER, attempt.callId, sdp = offer.description, sdpType = offer.type.wire, video = attempt.video))
    }

    /** A call a Dex browser placed: its offer arrives over the socket, then the peer is contacted with it. */
    private suspend fun runOutgoingRemote(attempt: Attempt) {
        val offer = withTimeoutOrNull(OFFER_WAIT_MS) { attempt.remoteOffer.await() }
        if (current(attempt) == null) return
        if (offer == null) {
            diag.note("dex never produced an offer")
            end("Dex did not start the call. Please try again.", CallOutcome.FAILED, playBusyTone = false)
            return
        }
        contactPeer(attempt, OutboundCallSignal(CallSignalType.OFFER, attempt.callId, sdp = offer.sdp, sdpType = offer.sdpType, video = attempt.video))
    }

    private suspend fun contactPeer(attempt: Attempt, offerSignal: OutboundCallSignal) {
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
    private fun startIceSender(attempt: Attempt, ice: IceQueue) {
        if (ice.sender != null) return
        ice.sender = scope.launch(attempt.jobs) {
            for (signal in ice.out) {
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
            is InboundSignal.RenegotiateAnswer -> handleRenegotiateAnswer(signal, current)
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
        val seat = dialing.seat ?: return
        diag.note("peer is ringing")
        transition(Machine.Ringing(dialing.attempt, dialing.session, seat))
        if (seat is Seat.Phone) audio.startRingback()
        startIceSender(dialing.attempt, seat.ice)
        armNoAnswerTimer(dialing.attempt)
    }

    /**
     * Only what this app can produce: a host candidate of sane length. There is no STUN or TURN
     * on either side, so a relayed or reflexive candidate is an address a peer wants us to probe.
     */
    private fun isAcceptableCandidate(c: IceCandidatePayload): Boolean {
        if (c.candidate.length > MAX_CANDIDATE_CHARS) return false
        return CandidateRewrite.typeOf(c.candidate) == "host"
    }

    /**
     * A candidate that arrives before this side has a session (the callee still rings) waits for
     * `accept`; dropping it would leave the callee with nothing to pair against the caller's
     * only cross-network route. A missing candidate is the caller's end-of-candidates marker.
     * While a move is in flight the candidate belongs to the connection being set up.
     */
    private fun onRemoteCandidate(c: IceCandidatePayload?, current: Machine.Live) {
        val callId = current.attempt.callId
        val move = current.move
        val seat = current.seat
        if (c == null) {
            diag.note("remote: end of candidates")
            when {
                move is Move.ToRemote -> remote.send(SeatEvent.Ice(move.clientId, callId, null))
                move is Move.ToPhone -> Unit
                seat is Seat.Remote -> remote.send(SeatEvent.Ice(seat.clientId, callId, null))
            }
            return
        }
        if (!isAcceptableCandidate(c)) {
            diag.note("remote candidate refused")
            return
        }
        when {
            move is Move.ToRemote -> {
                remote.send(SeatEvent.Ice(move.clientId, callId, c))
                diag.note("remote candidate to dex (moving) ${describe(c.candidate)}")
            }
            move is Move.ToPhone -> {
                move.media.link.addRemoteCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex ?: 0, c.candidate))
                diag.note("remote candidate to phone (moving) ${describe(c.candidate)}")
            }
            seat is Seat.Phone -> {
                seat.media.link.addRemoteCandidate(IceCandidate(c.sdpMid, c.sdpMLineIndex ?: 0, c.candidate))
                diag.note("remote candidate ${describe(c.candidate)}")
            }
            seat is Seat.Remote -> {
                remote.send(SeatEvent.Ice(seat.clientId, callId, c))
                diag.note("remote candidate to dex ${describe(c.candidate)}")
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
            val connecting = Machine.Connecting(attempt, still.session.copy(localVideo = tracks.videoTrack), Seat.Phone(link))
            transition(connecting)
            startIceSender(attempt, link.ice)
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
        // an answer belongs to a call this side placed and has a seat for
        if (current !is Machine.Dialing && current !is Machine.Ringing) return
        val seat = current.seat ?: return
        val attempt = current.attempt
        attempt.noAnswer?.cancel()
        attempt.noAnswer = null
        diag.note("answer received")
        startIceSender(attempt, seat.ice)
        when (seat) {
            is Seat.Remote -> {
                remote.send(SeatEvent.Sdp(seat.clientId, attempt.callId, signal.sdp, signal.sdpType ?: "answer"))
                transition(Machine.Connecting(attempt, current.session, seat))
                armLinkWatchdog(attempt, CONNECT_TIMEOUT_MS)
            }
            is Seat.Phone -> {
                audio.stopRingback()
                try {
                    seat.media.link.setRemote(SessionDescription(sdpType(signal.sdpType, SessionDescription.Type.ANSWER), signal.sdp))
                    val now = current(attempt) ?: return
                    transition(Machine.Connecting(attempt, now.session, seat))
                    armLinkWatchdog(attempt, CONNECT_TIMEOUT_MS)
                } catch (e: Exception) {
                    diag.note("answer rejected by libwebrtc: ${e.message}")
                    abort("The call could not connect. Please try again.", CallOutcome.FAILED)
                }
            }
        }
    }

    private suspend fun handleRenegotiate(signal: InboundSignal.Renegotiate, current: Machine.Live) {
        // an offer from the peer while this side is offering a move is glare; the move's own answer settles it
        if (current.move != null) return
        when (val seat = current.seat) {
            is Seat.Remote -> remote.send(SeatEvent.Sdp(seat.clientId, current.attempt.callId, signal.sdp, signal.sdpType ?: "offer"))
            is Seat.Phone -> try {
                val link = seat.media.link
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
            null -> Unit
        }
    }

    private suspend fun handleRenegotiateAnswer(signal: InboundSignal.RenegotiateAnswer, current: Machine.Live) {
        val callId = current.attempt.callId
        val move = current.move
        val seat = current.seat
        when {
            move is Move.ToRemote -> remote.send(SeatEvent.Sdp(move.clientId, callId, signal.sdp, signal.sdpType ?: "answer"))
            move is Move.ToPhone -> runCatching { move.media.link.setRemote(SessionDescription(sdpType(signal.sdpType, SessionDescription.Type.ANSWER), signal.sdp)) }
                .onFailure { failMoveToPhone("The phone could not take the call: ${it.message}") }
            seat is Seat.Remote -> remote.send(SeatEvent.Sdp(seat.clientId, callId, signal.sdp, signal.sdpType ?: "answer"))
            seat is Seat.Phone -> runCatching { seat.media.link.setRemote(SessionDescription(sdpType(signal.sdpType, SessionDescription.Type.ANSWER), signal.sdp)) }
                .onFailure { warningFlow.tryEmit("Couldn't switch the call's video: ${it.message}") }
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
        if (current.move != null) return
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

    // --- the remote seat --------------------------------------------------------------------

    private suspend fun dispatchRemote(command: RemoteCommand) {
        when (command) {
            is RemoteCommand.Start -> startRemoteCall(command)
            is RemoteCommand.Gone -> onRemoteGone(command.clientId)
            is RemoteCommand.Accept -> acceptRemote(command.clientId, command.callId)
            is RemoteCommand.Reject -> liveFor(command.callId)?.let { if (it is Machine.Incoming) reject() }
            is RemoteCommand.Hangup -> liveFor(command.callId)?.let { if (isSeatOf(it, command.clientId)) hangupNow(it) }
            is RemoteCommand.Sdp -> liveFor(command.callId)?.let { onRemoteSdp(it, command) }
            is RemoteCommand.Ice -> liveFor(command.callId)?.let { onRemoteIce(it, command) }
            is RemoteCommand.Connected -> liveFor(command.callId)?.let { onRemoteConnected(it, command.clientId) }
            is RemoteCommand.Failed -> liveFor(command.callId)?.let { onRemoteFailed(it, command.clientId, command.reason) }
            is RemoteCommand.Cam -> liveFor(command.callId)?.let { onRemoteCam(it, command) }
            is RemoteCommand.MoveToPhone -> liveFor(command.callId)?.let { moveToPhone(it, command.clientId) }
        }
    }

    private fun liveFor(callId: String): Machine.Live? = live?.takeIf { it.attempt.callId == callId }

    private suspend fun startRemoteCall(command: RemoteCommand.Start) {
        if (machine is Machine.Live) return
        if (!isKnownClient(command.clientId)) return
        val contact = core.contact(command.peerIp)
        if (contact?.isBlocked == true) return
        if (machine is Machine.Live) return
        if (!isKnownClient(command.clientId)) return

        val attempt = newAttempt(UUID.randomUUID().toString(), CallPeer(command.peerIp, contact?.label ?: command.peerIp), isIncoming = false, video = command.video)
        diag.begin("outgoing from dex", attempt.callId, command.peerIp)
        val seat = Seat.Remote(command.clientId)
        transition(Machine.Dialing(attempt, CallSession(callId = attempt.callId, peer = attempt.peer, video = command.video), seat))
        remote.send(SeatEvent.Media(command.clientId, attempt.callId, isOfferer = true, video = command.video))
        scope.launch(attempt.jobs) { runOutgoingRemote(attempt) }
    }

    /** A Dex browser takes an incoming call: it gets the offer and every candidate held so far; the phone stops ringing. */
    private fun acceptRemote(clientId: String, callId: String) {
        val incoming = liveFor(callId) as? Machine.Incoming ?: return
        if (!isKnownClient(clientId)) return
        val attempt = incoming.attempt
        system.hideIncoming()
        diag.note("accepted on dex")
        val seat = Seat.Remote(clientId)
        transition(Machine.Connecting(attempt, incoming.session, seat))
        startIceSender(attempt, seat.ice)
        armLinkWatchdog(attempt, CONNECT_TIMEOUT_MS)
        remote.send(SeatEvent.Media(clientId, attempt.callId, isOfferer = false, video = incoming.offer.video, remoteSdp = incoming.offer.sdp, remoteSdpType = incoming.offer.sdpType))
        for (c in incoming.heldRemote) remote.send(SeatEvent.Ice(clientId, attempt.callId, c))
        if (incoming.heldRemote.isNotEmpty()) diag.note("handed ${incoming.heldRemote.size} held remote candidates to dex")
    }

    private fun onRemoteSdp(current: Machine.Live, command: RemoteCommand.Sdp) {
        if (command.sdp.length > MAX_SDP_CHARS) return
        val attempt = current.attempt
        val isOffer = command.sdpType.equals("offer", ignoreCase = true)
        val move = moveTargetOf(current)
        if (move != null && move.clientId == command.clientId) {
            if (isOffer) sendQuietly(attempt.peer.ip, OutboundCallSignal(CallSignalType.RENEGOTIATE, attempt.callId, sdp = command.sdp, sdpType = command.sdpType), ANSWER_TIMEOUT_MS)
            return
        }
        if (!isSeatOf(current, command.clientId)) return
        when (current) {
            is Machine.Dialing -> if (isOffer) attempt.remoteOffer.complete(PendingOffer(command.sdp, command.sdpType, attempt.video))
            is Machine.Connecting -> if (!isOffer) {
                sendQuietly(attempt.peer.ip, OutboundCallSignal(CallSignalType.ANSWER, attempt.callId, sdp = command.sdp, sdpType = command.sdpType), ANSWER_TIMEOUT_MS)
                diag.note("answer sent from dex")
            }
            is Machine.Active -> if (current.move == null) {
                val type = if (isOffer) CallSignalType.RENEGOTIATE else CallSignalType.RENEGOTIATE_ANSWER
                sendQuietly(attempt.peer.ip, OutboundCallSignal(type, attempt.callId, sdp = command.sdp, sdpType = command.sdpType), ANSWER_TIMEOUT_MS)
            }
            is Machine.Ringing, is Machine.Incoming -> Unit
        }
    }

    /** A browser's candidates go to the peer as this phone's own: only the relayed ones reach it, and they travel as host candidates. */
    private fun onRemoteIce(current: Machine.Live, command: RemoteCommand.Ice) {
        val attempt = current.attempt
        val move = moveTargetOf(current)
        val ice = when {
            move != null && move.clientId == command.clientId -> move.ice
            isSeatOf(current, command.clientId) -> (current.seat as Seat.Remote).ice
            else -> return
        }
        val candidate = command.candidate
        if (candidate == null) {
            ice.out.trySend(OutboundCallSignal(CallSignalType.ICE, attempt.callId, candidate = null))
            diag.note("dex: end of candidates")
            return
        }
        val rewritten = CandidateRewrite.forPeer(candidate) ?: run {
            diag.note("dex candidate dropped: not relayed")
            return
        }
        if (ice.out.trySend(OutboundCallSignal(CallSignalType.ICE, attempt.callId, candidate = rewritten)).isFailure) {
            diag.note("dex candidate dropped: buffer full")
        } else {
            diag.note("dex candidate ${describe(rewritten.candidate)}")
        }
    }

    private fun onRemoteConnected(current: Machine.Live, clientId: String) {
        val move = moveTargetOf(current)
        if (move != null && move.clientId == clientId) {
            finishMoveToRemote(current as Machine.Active, move)
            return
        }
        if (!isSeatOf(current, clientId)) return
        val attempt = current.attempt
        when (current) {
            is Machine.Connecting -> {
                attempt.watchdog?.cancel()
                attempt.watchdog = null
                attempt.noAnswer?.cancel()
                attempt.noAnswer = null
                diag.note("connected on dex")
                transition(Machine.Active(attempt, current.session, current.seat, now(), isLinkUp = true))
            }
            is Machine.Active -> if (!current.isLinkUp) {
                attempt.watchdog?.cancel()
                attempt.watchdog = null
                diag.note("dex link is back")
                transition(current.withLink(isUp = true))
            }
            is Machine.Dialing, is Machine.Ringing, is Machine.Incoming -> Unit
        }
    }

    private fun onRemoteFailed(current: Machine.Live, clientId: String, reason: String) {
        val move = moveTargetOf(current)
        if (move != null && move.clientId == clientId) {
            failMoveToRemote(reason)
            return
        }
        if (!isSeatOf(current, clientId)) return
        diag.note("dex reported failure: $reason")
        abort(reason, if (current is Machine.Active) null else CallOutcome.FAILED, playBusyTone = false)
    }

    private fun onRemoteCam(current: Machine.Live, command: RemoteCommand.Cam) {
        if (!isSeatOf(current, command.clientId)) return
        updateSession { it.copy(camOff = !command.isOn) }
        sendQuietly(current.attempt.peer.ip, OutboundCallSignal(CallSignalType.CAM, current.attempt.callId, video = command.isOn), 0)
    }

    private fun onRemoteGone(clientId: String) {
        val current = live ?: return
        val move = moveTargetOf(current)
        if (move != null && move.clientId == clientId) {
            failMoveToRemote("Dex disconnected.")
            return
        }
        if (!isSeatOf(current, clientId)) return
        diag.note("dex seat disconnected")
        abort("Dex disconnected.", if (current is Machine.Active) null else CallOutcome.FAILED, playBusyTone = false)
    }

    // --- moving the seat --------------------------------------------------------------------

    private fun moveToRemoteNow(clientId: String) {
        val active = live as? Machine.Active ?: return
        if (active.seat !is Seat.Phone || active.move != null) return
        if (!isKnownClient(clientId)) return
        val attempt = active.attempt
        val ice = IceQueue()
        val deadline = scope.launch(attempt.jobs) {
            delay(MOVE_TIMEOUT_MS)
            if ((moveTargetOf(current(attempt) ?: return@launch))?.clientId == clientId) failMoveToRemote("Dex did not connect in time.")
        }
        transition(active.withMove(Move.ToRemote(clientId, ice, deadline)))
        startIceSender(attempt, ice)
        diag.note("moving to dex")
        remote.send(SeatEvent.Media(clientId, attempt.callId, isOfferer = true, video = active.session.video || !active.session.camOff))
    }

    private fun finishMoveToRemote(active: Machine.Active, move: Move.ToRemote) {
        val phone = active.seat as? Seat.Phone ?: return
        move.deadline.cancel()
        dispose(listOf(phone.media))
        audio.setProximityEnabled(false)
        audio.stop(playBusyTone = false)
        system.stopSession()
        system.hideFloating()
        transition(active.withSeat(Seat.Remote(move.clientId, move.ice), active.session.copy(localVideo = null, remoteVideo = null)))
        diag.note("call moved to dex")
    }

    /** The browser never came up: it is released and the phone's own connection is restarted to reclaim the peer. */
    private fun failMoveToRemote(reason: String) {
        val active = live as? Machine.Active ?: return
        val move = active.move as? Move.ToRemote ?: return
        val attempt = active.attempt
        move.deadline.cancel()
        move.ice.out.close()
        remote.send(SeatEvent.Release(move.clientId, attempt.callId, reason))
        transition(active.withMove(null))
        val phone = active.seat as? Seat.Phone ?: return
        diag.note("move to dex failed: $reason; reclaiming")
        scope.launch(attempt.jobs) {
            try {
                phone.media.link.restartIce()
                current(attempt)?.let { renegotiate(it) }
            } catch (e: Exception) {
                diag.note("reclaim offer failed: ${e.message}")
            }
        }
        armLinkWatchdog(attempt, CONNECT_TIMEOUT_MS)
    }

    private suspend fun moveToPhone(current: Machine.Live, clientId: String) {
        val active = current as? Machine.Active ?: return
        val seat = active.seat as? Seat.Remote ?: return
        if (seat.clientId != clientId || active.move != null) return
        val attempt = active.attempt
        val wantsCamera = !active.session.camOff
        diag.note("moving to phone")
        val tracks = try {
            withContext(workDispatcher) { media.openLocal(wantsCamera) }
        } catch (e: Exception) {
            diag.note("local media unavailable for the move: ${e.message}")
            warningFlow.tryEmit(mediaUnavailableMessage(wantsCamera, isAnswering = true))
            return
        }
        val still = current(attempt) as? Machine.Active
        if (still == null || still.move != null || still.seat !is Seat.Remote) {
            withContext(workDispatcher) { tracks.release() }
            return
        }
        val newMedia = openLink(attempt, tracks, heldRemote = emptyList())
        val deadline = scope.launch(attempt.jobs) {
            delay(MOVE_TIMEOUT_MS)
            if (current(attempt)?.move is Move.ToPhone) failMoveToPhone("The phone did not connect in time.")
        }
        transition(still.withMove(Move.ToPhone(newMedia, deadline)))
        startIceSender(attempt, newMedia.ice)
        try {
            val offer = newMedia.link.createOffer()
            core.sendSignal(
                attempt.peer.ip,
                OutboundCallSignal(CallSignalType.RENEGOTIATE, attempt.callId, sdp = offer.description, sdpType = offer.type.wire),
                ANSWER_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            failMoveToPhone("The phone could not offer the call: ${e.message}")
        }
    }

    private fun finishMoveToPhone(active: Machine.Active, move: Move.ToPhone) {
        val seat = active.seat as? Seat.Remote ?: return
        val attempt = active.attempt
        move.deadline.cancel()
        seat.ice.out.close()
        remote.send(SeatEvent.Release(seat.clientId, attempt.callId, "moved"))
        val tracks = move.media.tracks
        val session = active.session.copy(localVideo = tracks.videoTrack, isFrontCamera = tracks.isFrontCamera, camOff = tracks.videoTrack == null)
        transition(active.withSeat(Seat.Phone(move.media), session))
        audio.start(session.speaker)
        audio.setProximityEnabled(true)
        system.startSession(attempt.peer.name, session.video, active.connectedAt)
        move.media.link.describeSelectedPair { line -> scope.launch { if (current(attempt) != null) diag.note("media path: $line") } }
        diag.note("call moved to phone")
    }

    /** The phone never came up: its media goes, and the browser restarts ICE to reclaim the peer. */
    private fun failMoveToPhone(reason: String) {
        val active = live as? Machine.Active ?: return
        val move = active.move as? Move.ToPhone ?: return
        val attempt = active.attempt
        move.deadline.cancel()
        dispose(listOf(move.media))
        transition(active.withMove(null))
        val seat = active.seat as? Seat.Remote ?: return
        diag.note("move to phone failed: $reason; dex reclaims")
        warningFlow.tryEmit(reason)
        remote.send(SeatEvent.Media(seat.clientId, attempt.callId, isOfferer = true, video = active.session.video || !active.session.camOff, isRestart = true))
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
        val media = Media(tracks, link, IceQueue())
        media.collector = scope.launch(attempt.jobs) {
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
        val incomingMove = (current.move as? Move.ToPhone)?.takeIf { it.media === media }
        // a report from a connection that is no longer this call's (released after a move) changes nothing
        if (current.media !== media && incomingMove == null) return
        when (event) {
            is LinkEvent.LocalCandidate -> {
                val payload = IceCandidatePayload(event.candidate.sdp, event.candidate.sdpMid, event.candidate.sdpMLineIndex)
                // queued in order; the sender starts once the peer is known to be there
                if (media.ice.out.trySend(OutboundCallSignal(CallSignalType.ICE, attempt.callId, candidate = payload)).isFailure) {
                    diag.note("local candidate dropped: buffer full")
                } else {
                    diag.note("local candidate ${describe(event.candidate.sdp)}")
                }
            }
            LinkEvent.GatheringComplete -> {
                diag.note("local: end of candidates")
                media.ice.out.trySend(OutboundCallSignal(CallSignalType.ICE, attempt.callId, candidate = null))
            }
            is LinkEvent.RemoteVideo -> updateSession { it.copy(remoteVideo = event.track) }
            LinkEvent.Connected -> if (incomingMove != null) finishMoveToPhone(current as Machine.Active, incomingMove) else activate(current)
            LinkEvent.IceFailed -> if (incomingMove != null) failMoveToPhone("The phone could not connect.") else onLinkFailed(current)
            LinkEvent.Closed -> if (incomingMove != null) failMoveToPhone("The phone's connection closed.") else abort("Connection lost.", null)
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
        val seat = current.seat as? Seat.Phone ?: return
        val attempt = current.attempt
        attempt.watchdog?.cancel()
        attempt.watchdog = null
        seat.media.link.describeSelectedPair { line -> scope.launch { if (current(attempt) != null) diag.note("media path: $line") } }
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
        transition(Machine.Active(attempt, current.session, seat, connectedAt, isLinkUp = true))
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
        val seat = current.seat
        if (seat !is Seat.Remote) audio.stop(playBusyTone)
        if (seat is Seat.Remote) {
            seat.ice.out.close()
            remote.send(SeatEvent.Release(seat.clientId, current.attempt.callId, reason ?: "ended"))
        }
        (current.move as? Move.ToRemote)?.let {
            it.ice.out.close()
            remote.send(SeatEvent.Release(it.clientId, current.attempt.callId, reason ?: "ended"))
        }

        val ended = CallState.Ended(current.session.peer, current.session.video, reason)
        transition(Machine.Ending(ended, disposal = null))
        val toDispose = listOfNotNull(current.media, (current.move as? Move.ToPhone)?.media)
        if (toDispose.isEmpty()) return
        machine = Machine.Ending(ended, dispose(toDispose))
    }

    /** Releases connections off the main thread; their collectors are stopped first so nothing they report lands on the next state. */
    private fun dispose(medias: List<Media>): Job {
        for (m in medias) m.collector?.cancel()
        return scope.launch(workDispatcher) {
            for (m in medias) {
                m.ice.out.close()
                m.tracks.release()
                m.link.close()
            }
        }
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
        val type = CandidateRewrite.typeOf(candidateSdp) ?: "?"
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
        /** A Dex browser placing a call has this long to produce its offer. */
        const val OFFER_WAIT_MS = 10_000L
        /** A seat move that has not connected by then is undone. */
        const val MOVE_TIMEOUT_MS = 20_000L
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

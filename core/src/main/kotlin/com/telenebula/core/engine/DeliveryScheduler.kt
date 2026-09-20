package com.telenebula.core.engine

import com.telenebula.core.CoreLog
import com.telenebula.core.Ip
import com.telenebula.core.events.CoreEvent
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.PeerPresence
import com.telenebula.core.model.PeerQueueState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Delivery, accounted **per peer rather than per action**.
 *
 * The old outbox gave every pending action its own attempt on a fixed cycle, so twenty actions
 * queued for one absent peer cost twenty connects, twenty row writes and twenty ack timeouts every
 * minute. Here a peer is the unit: it is probed once, and only if it answers does its whole queue
 * move, sequentially, over the link that probe already opened. Twenty actions for a silent peer
 * cost one probe.
 *
 * Nothing is per-peer *shaped* in the runtime either: one timer and [Limits.DRAIN_WORKERS] workers
 * serve every peer there is, so a thousand silent contacts add no coroutines at all.
 *
 * Silence never fails an action. A peer that does not answer climbs [Limits.PING_BACKOFF_MS] to a
 * five-minute floor and is probed there for as long as it takes; any proof of life — a frame, the
 * tunnel returning, a ping the user asked for — puts it straight back on the fast end and drains it.
 */
internal class DeliveryScheduler(private val engine: Engine) {
    private val schedules = ConcurrentHashMap<String, PeerSchedule>()

    /** when a round trip of ours last answered; the only reachability that lets a drain skip its probe */
    private val lastAnsweredAt = ConcurrentHashMap<String, Long>()

    /**
     * Wakes the timer before its next due time; conflated, because one wake is as good as ten.
     * The workers have their own channel on purpose — sharing one would let a worker swallow a
     * wake meant for the timer, and nothing would ever be scheduled again.
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** peers the timer has released to the workers; bounded, and a full buffer makes trySend fail so the pass re-offers next time */
    private val work = Channel<String>(capacity = Limits.MAX_DRAIN_PEERS.toInt())

    fun start() {
        engine.scope.launch { timerLoop() }
        repeat(Limits.DRAIN_WORKERS) { engine.scope.launch { workerLoop() } }
    }

    // --- triggers ---

    /** New work for a peer: probe it now rather than at whatever rung it had climbed to. */
    fun kick(peerIp: String) {
        val ip = Ip.normalize(peerIp)
        if (ip.isEmpty()) return
        schedules[ip]?.let { if (it.isDraining) it.wokenWhileDraining = true else it.tryAt(now()) }
        wake.trySend(Unit)
    }

    /**
     * Something arrived from this peer: it is there, so the ladder resets and the drain runs now.
     * It does not excuse that drain from its own probe — a frame proves the peer reached us, not
     * that we can reach it, and only a round trip of ours says a queue may move.
     */
    fun onPeerHeard(peerIp: String) {
        val ip = Ip.normalize(peerIp)
        if (ip.isEmpty()) return
        val schedule = schedules[ip] ?: return
        schedule.onAnswered(now())
        wake.trySend(Unit)
    }

    /**
     * A ping the user asked for, which answered. The whole point of the manual ping is that it does
     * not merely report: it proves the peer is there, so whatever is queued goes now instead of
     * waiting out the rung the peer had climbed to.
     */
    fun onProbeSucceeded(peerIp: String) {
        val ip = Ip.normalize(peerIp)
        if (ip.isEmpty()) return
        rememberAnswered(ip)
        onPeerHeard(ip)
    }

    fun onTunnelUp() {
        val stamp = now()
        // jittered, or every peer that went away when the tunnel dropped comes back on one tick
        for (schedule in schedules.values) schedule.tryAt(stamp + Random.nextLong(TUNNEL_SPREAD_MS))
        wake.trySend(Unit)
    }

    /**
     * The tunnel went down. Nobody's backoff is escalated — this is our outage, not theirs — but
     * every cached connection goes, because a TCP session whose tunnel vanished accepts writes and
     * never resets.
     */
    fun onTunnelDown() {
        for (schedule in schedules.values) schedule.onTunnelLost()
        for (ip in engine.peers.keys.toList()) engine.transport.evict(ip)
        emitAll()
        wake.trySend(Unit)
    }

    fun forget(peerIp: String) {
        val ip = Ip.normalize(peerIp)
        lastAnsweredAt.remove(ip)
        val schedule = schedules[ip] ?: return
        if (schedule.isDraining) schedule.isForgotten = true else schedules.remove(ip)
    }

    fun stateOf(peerIp: String): PeerQueueState? = schedules[Ip.normalize(peerIp)]?.snapshot(now(), engine.isTunnelUp.get())

    // --- the timer ---

    private suspend fun timerLoop() {
        while (currentCoroutineContext().isActive) {
            val sleep = runCatching { pass() }.getOrElse {
                CoreLog.warn(TAG, "scheduler pass: ${it.message}")
                engine.events.fault("Delivery scheduling failed", it.describe())
                Limits.SCHEDULER_MAX_SLEEP_MS
            }
            withTimeoutOrNull(sleep) { wake.receive() }
        }
    }

    /** One pass: refresh the peer set from a single coarse read, release what is due, say when to wake. */
    private fun pass(): Long {
        if (!engine.isTunnelUp.get()) return Limits.SCHEDULER_MAX_SLEEP_MS

        val rows = engine.store.peersWithOpenActions(Limits.MAX_DRAIN_PEERS)
        val live = HashSet<String>(rows.size)
        for (row in rows) {
            live += row.peerIp
            val schedule = schedules.getOrPut(row.peerIp) { PeerSchedule(row.peerIp) }
            schedule.queued = row.queued
        }
        // a peer whose queue emptied (or whose contact was deleted) stops being tracked, which is
        // what keeps this map bounded by peers that have work rather than by the contact list
        for (ip in schedules.keys.toList()) {
            val schedule = schedules[ip] ?: continue
            if (ip !in live && !schedule.isDraining) {
                schedules.remove(ip)
                engine.events.peerQueue(schedule.snapshot(now(), true).copy(queued = 0))
            }
        }
        purgeAnswered()

        val stamp = now()
        var soonest = Long.MAX_VALUE
        for (schedule in schedules.values) {
            // a peer already handed to a worker is neither released again nor waited for
            if (schedule.isDraining) continue
            if (!schedule.isDue(stamp)) {
                soonest = minOf(soonest, schedule.dueAtMs)
                continue
            }
            // claimed here rather than in the worker, so this pass cannot release it twice and the
            // sleep below cannot spin on a peer that is due but already in hand
            schedule.isDraining = true
            if (work.trySend(schedule.ip).isFailure) {
                schedule.isDraining = false
                soonest = minOf(soonest, stamp + RETRY_RELEASE_MS)
            }
        }
        emitAll()
        return if (soonest == Long.MAX_VALUE) Limits.SCHEDULER_MAX_SLEEP_MS
        else (soonest - stamp).coerceIn(RETRY_RELEASE_MS, Limits.SCHEDULER_MAX_SLEEP_MS)
    }

    // --- the workers ---

    private suspend fun workerLoop() {
        for (ip in work) {
            try {
                drain(ip)
            } catch (e: Exception) {
                CoreLog.warn(TAG, "drain $ip: ${e.message}")
                engine.events.fault("Delivery to $ip failed", e.describe())
            } finally {
                // whatever happened, the peer is back in the timer's hands
                schedules[ip]?.let { schedule ->
                    schedule.isDraining = false
                    if (schedule.isForgotten) schedules.remove(ip, schedule)
                }
                wake.trySend(Unit)
            }
        }
    }

    /**
     * One peer: prove it is there, then move everything it has. The probe is the only reachability
     * test the whole queue pays for, and the first silence ends the pass — a peer that went away
     * mid-drain must not cost an ack timeout for each action still waiting.
     */
    private suspend fun drain(ip: String) {
        val schedule = schedules[ip] ?: return
        if (!engine.isTunnelUp.get()) return
        schedule.wokenWhileDraining = false
        emit(schedule)

        if (!isFresh(ip) && engine.transport.probe(ip) < 0) {
            engine.events.emit(CoreEvent.PresenceChanged(ip, PeerPresence.OFFLINE))
            schedule.onSilent(now(), ::jitter)
            emit(schedule)
            return
        }
        schedule.onAnswered(now())
        rememberAnswered(ip)

        var pass = 0
        var movedAnything = false
        while (pass++ < Limits.DRAIN_PASSES && currentCoroutineContext().isActive) {
            val open = engine.store.pendingActionsForPeer(ip, Limits.PEER_DRAIN_PAGE)
            if (open.isEmpty()) break
            val delivered = runQueue(open, schedule)
            if (delivered == PEER_WENT_QUIET) return
            // a pass that moved nothing is one whose every action is stalled behind a refusal or a
            // parked offer; running the identical page again would do the same nothing
            if (delivered == 0) break
            movedAnything = true
        }

        // Rows stalled behind a refusal are still "queued" for the user, but they are not work this
        // peer can be probed into doing — only something that could actually run justifies waking
        // for it again, or a permanently stuck message would poll its peer forever.
        schedule.queued = engine.store.openActionCount(ip)
        val more = movedAnything && engine.store.pendingActionCount(ip) > 0
        // a kick that landed after the count above still counts as work, or the action that caused
        // it would sit until something unrelated woke this peer
        if (more || schedule.wokenWhileDraining) {
            schedule.dueIn(now(), Limits.DRAIN_RESUME_MS)
        } else {
            schedule.sleepUntilWoken()
        }
        emit(schedule)
    }

    /**
     * The peer's queue, grouped by message so per-message order is strict while a message stalled
     * behind a refusal does not hold up the rest. Returns how many actions moved, or
     * [PEER_WENT_QUIET] once the peer stopped answering.
     */
    private suspend fun runQueue(open: List<MessageAction>, schedule: PeerSchedule): Int {
        var delivered = 0
        val groups = open.groupByTo(LinkedHashMap()) { it.messageId }
        for (group in groups.values) {
            for (action in group) {
                when (engine.sender.attempt(action.id)) {
                    AttemptResult.DELIVERED -> delivered++ // on to this message's next action, in order
                    AttemptResult.SKIPPED, AttemptResult.REFUSED -> break // this message stalls; the peer is fine
                    AttemptResult.SILENT -> {
                        engine.events.emit(CoreEvent.PresenceChanged(schedule.ip, PeerPresence.OFFLINE))
                        schedule.onSilent(now(), ::jitter)
                        emit(schedule)
                        return PEER_WENT_QUIET
                    }
                }
            }
        }
        return delivered
    }

    // --- reachability memory ---

    private fun isFresh(ip: String): Boolean {
        val answered = lastAnsweredAt[ip] ?: return false
        return now() - answered < Limits.PEER_FRESH_MS
    }

    private fun rememberAnswered(ip: String) {
        lastAnsweredAt[ip] = now()
        if (lastAnsweredAt.size > Limits.PEER_FRESH_CACHE) purgeAnswered()
    }

    private fun purgeAnswered() {
        val deadline = now() - Limits.PEER_FRESH_MS
        for ((ip, at) in lastAnsweredAt.entries.toList()) if (at < deadline) lastAnsweredAt.remove(ip, at)
    }

    // --- events ---

    private fun emit(schedule: PeerSchedule) {
        engine.events.peerQueue(schedule.snapshot(now(), engine.isTunnelUp.get()))
    }

    private fun emitAll() {
        val stamp = now()
        val up = engine.isTunnelUp.get()
        for (schedule in schedules.values) engine.events.peerQueue(schedule.snapshot(stamp, up))
    }

    private fun jitter(delayMs: Long): Long {
        if (delayMs <= 0) return delayMs
        val spread = (delayMs * Limits.PING_JITTER).toLong().coerceAtLeast(1)
        return delayMs + Random.nextLong(-spread, spread + 1)
    }

    companion object {
        private const val TAG = "TnDelivery"

        /** how far a tunnel-up burst is spread, so fifty peers do not connect on one tick */
        private const val TUNNEL_SPREAD_MS = 3_000L

        /** floor on the timer's sleep, so a due peer that could not be released does not spin */
        private const val RETRY_RELEASE_MS = 50L

        /** the peer stopped answering mid-drain: the rest of its queue waits for the next probe */
        private const val PEER_WENT_QUIET = -1

        /** monotonic: a wall clock would park every peer for hours the first time NTP corrected it */
        fun now(): Long = System.nanoTime() / 1_000_000
    }
}

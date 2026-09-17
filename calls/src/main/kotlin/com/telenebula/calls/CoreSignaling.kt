package com.telenebula.calls

/** The contact facts the engine needs before ringing or dialing. */
data class CallContact(val label: String, val isBlocked: Boolean, val callsAllowed: Boolean)

data class CallLogEntry(
    val id: String,
    val peerIp: String,
    val isIncoming: Boolean,
    val isVideo: Boolean,
    val outcome: CallOutcome,
    val startedAt: Long,
    val connectedAt: Long?,
    val endedAt: Long,
)

/** What the messaging core does for calls; implemented by the app over the core client. */
interface CoreSignaling {
    /** Queues one signal frame; throws when the peer cannot be reached within [timeoutMs] (0 = core default). */
    suspend fun sendSignal(peerIp: String, signal: OutboundCallSignal, timeoutMs: Int)

    /** Round-trip ms, or a negative number when the peer did not answer in time. */
    suspend fun pingPeer(peerIp: String, timeoutMs: Int): Long

    suspend fun contact(ip: String): CallContact?

    /** Creates the contact when unknown and syncs the announced username. */
    suspend fun ensureContact(ip: String, name: String)

    suspend fun logCall(entry: CallLogEntry)

    fun normalizeIp(ip: String): String
}

/** Preference snapshot read at the moment a call starts or rings. */
data class CallPrefs(
    val ringForCalls: Boolean,
    val vibrateWhileRinging: Boolean,
    val missedNotification: Boolean,
    val videoSpeakerDefault: Boolean,
    /** the Network screen's verbose switch: libwebrtc logs and the call trail go to logcat too */
    val isVerboseLogging: Boolean = false,
)

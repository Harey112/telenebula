package com.telenebula.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PeerStats(
    val messagesSent: Int = 0,
    val messagesReceived: Int = 0,
    val mediaSent: Int = 0,
    val mediaReceived: Int = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val firstMessageAt: Long? = null,
    val lastActivityAt: Long? = null,
    val pendingActions: Int = 0,
    val failedActions: Int = 0,
    /** a live TCP link to the peer exists right now */
    val isConnected: Boolean = false,
)

/**
 * What one peer's outbound queue is doing. Delivery is accounted per peer, not per action: this is
 * the whole of what the UI needs to explain why something has not gone yet.
 */
@Serializable
data class PeerQueueState(
    val ip: String = "",
    /** actions still to deliver, pending and parked together */
    val queued: Int = 0,
    /** the last probe, or a frame from the peer, said it is there */
    val isReachable: Boolean = false,
    /** a worker is probing or draining this peer right now */
    val isDraining: Boolean = false,
    /** ms until the next probe; 0 = as soon as a worker frees up, null = nothing scheduled */
    val nextProbeInMs: Long? = null,
    /** false while the tunnel is down: nobody is being probed, which is not the peer's fault */
    val isTunnelUp: Boolean = true,
)

@Serializable
data class NetworkStats(
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val connectedPeers: List<String> = emptyList(),
    val pendingActions: Int = 0,
    val failedActions: Int = 0,
    val uptimeMs: Long = 0,
)

@Serializable
data class StorageStats(
    val dbBytes: Long = 0,
    val attachmentsBytes: Long = 0,
    val attachmentsCount: Int = 0,
    val orphanBytes: Long = 0,
    val orphanCount: Int = 0,
    /** partial downloads a returning peer can still finish; kept, and not counted as orphans */
    val partialBytes: Long = 0,
    val partialCount: Int = 0,
    val messages: Int = 0,
    val contacts: Int = 0,
)

@Serializable
enum class NebulaLogLevel {
    @SerialName("info") INFO,
    @SerialName("debug") DEBUG,
}

/** What a peer's last pong said about it; a probe it did not answer means OFFLINE. */
enum class PeerPresence { ONLINE, REACHABLE, OFFLINE }

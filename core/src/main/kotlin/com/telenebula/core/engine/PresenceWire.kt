package com.telenebula.core.engine

import com.telenebula.core.model.PeerPresence

internal object PresenceWire {
    private const val ONLINE = "online"
    private const val REACHABLE = "reachable"

    fun encode(isOnline: Boolean): String = if (isOnline) ONLINE else REACHABLE

    /** a pong without the field, or with a value this build does not know, still proves the peer is there */
    fun decode(value: String?): PeerPresence = if (value == ONLINE) PeerPresence.ONLINE else PeerPresence.REACHABLE
}

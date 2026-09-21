package com.telenebula.calls

/**
 * A Dex browser's ICE candidates as the peer must see them. Its host and reflexive candidates are
 * LAN addresses the peer cannot reach; its relayed one is this phone's overlay address, and the
 * peer only pairs against host candidates, so that is what it is called on the wire.
 */
object CandidateRewrite {
    fun typeOf(candidate: String): String? {
        val parts = candidate.split(' ')
        val at = parts.indexOf("typ")
        return if (at >= 0) parts.getOrNull(at + 1) else null
    }

    /** null when the candidate cannot reach the peer at all. */
    fun forPeer(c: IceCandidatePayload): IceCandidatePayload? {
        if (c.candidate.length > CallEngine.MAX_CANDIDATE_CHARS) return null
        if (typeOf(c.candidate) != "relay") return null
        val parts = c.candidate.split(' ').toMutableList()
        val at = parts.indexOf("typ")
        if (at < 0 || at + 1 >= parts.size) return null
        parts[at + 1] = "host"
        var i = at + 2
        while (i < parts.size) {
            if ((parts[i] == "raddr" || parts[i] == "rport") && i + 1 < parts.size) {
                parts.removeAt(i)
                parts.removeAt(i)
            } else {
                i++
            }
        }
        return IceCandidatePayload(parts.joinToString(" "), c.sdpMid, c.sdpMLineIndex)
    }
}

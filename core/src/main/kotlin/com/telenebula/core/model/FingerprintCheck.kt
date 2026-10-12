package com.telenebula.core.model

/** What comparing a peer's authenticated certificate with the pinned one found. */
enum class FingerprintCheck {
    /** no contact at that address, or one whose request is still open */
    UNKNOWN,
    PINNED,
    MATCHES,
    /** a different certificate than the pinned one, seen for the first time */
    CHANGED,
    /** the same unconfirmed certificate as last time */
    STILL_CHANGED,
}

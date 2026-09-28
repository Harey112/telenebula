package com.telenebula.dex

/** Every bound the Dex server enforces, and why. Nothing in this module grows without one of these. */
object Limits {
    /** Accepted TCP connections at once; a browser opens a handful, a scan opens hundreds. */
    const val MAX_CONNECTIONS = 32

    /** Logged-in browsers with a live socket; the setting caps it lower, never higher. */
    const val MAX_CLIENTS = 8

    /** Request line plus headers; a browser never needs more than a couple of KiB. */
    const val MAX_HEAD_BYTES = 16 * 1024

    /** JSON bodies (login); anything bigger is not a form. */
    const val MAX_JSON_BODY_BYTES = 16 * 1024

    /** One uploaded attachment; streamed to disk, so this bounds disk, not memory. */
    const val MAX_UPLOAD_BYTES = 512L * 1024 * 1024

    /** One WebSocket text message from a browser: a frame is a command, not a payload. */
    const val MAX_WS_MESSAGE_BYTES = 64 * 1024

    /** Server-to-browser frames waiting on a slow socket; past this the client is dropped and resyncs on reconnect. */
    const val WS_OUT_QUEUE = 256

    /** A keep-alive connection with nothing to say is closed after this. */
    const val IDLE_TIMEOUT_MS = 30_000

    /** A body or handshake that stalls this long is abandoned. */
    const val READ_TIMEOUT_MS = 15_000

    /** WebSocket ping cadence; two unanswered pings drop the client. */
    const val WS_PING_INTERVAL_MS = 20_000L

    /** Failed logins from one address before it must wait. */
    const val LOGIN_FAILS_BEFORE_LOCK = 5

    /** How long a locked address waits. */
    const val LOGIN_LOCK_MS = 60_000L

    /** A session not seen for this long is forgotten; the browser logs in again. */
    const val SESSION_IDLE_MS = 7L * 24 * 60 * 60 * 1000

    /** Messages per chat page over the socket. */
    const val CHAT_PAGE = 100

    /** Bytes of one attachment response chunk; fits a socket buffer, keeps memory flat however large the file. */
    const val STREAM_CHUNK_BYTES = 64 * 1024

    /** TURN allocations at once: one per call per browser, with slack for a browser that reconnects mid-call. */
    const val MAX_TURN_ALLOCATIONS = 8

    /** Peer permissions per allocation; a peer phone has a handful of candidates. */
    const val MAX_TURN_PERMISSIONS = 16

    /** Bound channels per allocation. */
    const val MAX_TURN_CHANNELS = 16

    /** Longest allocation lifetime a browser may ask for (RFC 5766 §6.2). */
    const val MAX_TURN_LIFETIME_S = 3_600

    /** Default allocation lifetime (RFC 5766 §6.2). */
    const val DEFAULT_TURN_LIFETIME_S = 600

    /** Permission lifetime (RFC 5766 §8). */
    const val TURN_PERMISSION_MS = 300_000L

    /** Channel binding lifetime (RFC 5766 §11). */
    const val TURN_CHANNEL_MS = 600_000L

    /** One UDP datagram; media packets are well under the path MTU, and STUN messages are small. */
    const val MAX_UDP_BYTES = 4_096

    /** A TURN credential dies with its call or its browser; this caps one that is never revoked. */
    const val TURN_CREDENTIAL_MS = 8L * 60 * 60 * 1000

    /** Answers sent before a credential is checked (Binding, 401, 438): a spoofed source must not turn the relay into an amplifier. */
    const val TURN_UNAUTHENTICATED_PER_S = 20

    /** The burst those answers may reach: one browser's ICE start sends a handful at once. */
    const val TURN_UNAUTHENTICATED_BURST = 40

    /** A session this old logs in again however active it is, so a stolen cookie does not live for ever. */
    const val SESSION_MAX_MS = 30L * 24 * 60 * 60 * 1000

    /** How often live sockets are checked against their session's expiry. */
    const val SESSION_CHECK_MS = 30_000L

    /** Bytes of server-to-browser frames waiting on a slow socket; a chat snapshot is large, a queue of them is not. */
    const val WS_OUT_QUEUE_BYTES = 8L * 1024 * 1024

    /** PBKDF2 checks at once; each costs a core for a moment, and a login flood must not starve the phone. */
    const val MAX_PASSWORD_CHECKS = 2

    /** Uploads streaming in at once across every browser. */
    const val MAX_UPLOADS = 4

    /** Uploads streaming in at once from one login. */
    const val MAX_UPLOADS_PER_SESSION = 2

    /** Bytes of uploads staged on disk at once, however many browsers are sending. */
    const val MAX_STAGING_BYTES = 1024L * 1024 * 1024

    /** Space an upload must leave free, so a browser can never fill the phone's storage. */
    const val UPLOAD_FREE_SPACE_RESERVE = 256L * 1024 * 1024

    /** The phone's interfaces are re-read at most this often when a connection comes from an address they did not have. */
    const val ADMISSION_REFRESH_MS = 1_000L

    /** Consecutive failures of a socket loop before the streak is reported. */
    const val LOOP_FAILURES_BEFORE_REPORT = 5

    /** First wait after a failed accept or receive; doubles per failure. */
    const val LOOP_BACKOFF_MIN_MS = 10L

    /** Longest wait between retries of a failing socket loop. */
    const val LOOP_BACKOFF_MAX_MS = 1_000L
}

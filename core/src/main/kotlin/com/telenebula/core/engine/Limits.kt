package com.telenebula.core.engine

/**
 * Everything the engine is bounded by. There are no unbounded queues, caches or waits here: each
 * value below is the reason some part of the engine cannot grow without limit.
 */
internal object Limits {
    const val CONNECT_TIMEOUT_MS = 8_000L
    const val ACK_TIMEOUT_MS = 8_000L

    /** a reachability probe gives up waiting for the pong after this */
    const val PING_TIMEOUT_MS = 5_000L

    /** attachment bytes per att-chunk frame (base64 adds ~33%, keeping it under the frame cap) */
    const val ATT_CHUNK_BYTES = 64 * 1024

    /** the ack for a chunked transfer arrives only after the last chunk */
    const val ATT_ACK_TIMEOUT_MS = 60_000L

    /** incoming transfers idle longer than this are discarded */
    const val TRANSFER_STALE_MS = 120_000L

    /** A transfer row untouched this long has no peer coming back for it; the sweep stops sparing its partial file. */
    const val TRANSFER_ABANDON_MS = 7 * 24 * 60 * 60_000L

    /**
     * The probe ladder. A peer holding a queue is probed now, then after 5 s, 15 s, a minute, and
     * from then on every five minutes for as long as it stays silent. It is the floor that makes
     * "keep pinging until the queue drains" affordable: a peer gone for a week costs one probe per
     * five minutes, not one per action per cycle.
     */
    val PING_BACKOFF_MS = longArrayOf(0L, 5_000L, 15_000L, 60_000L, 300_000L)

    /**
     * Spread applied to every scheduled probe, as a fraction. Without it every peer that went away
     * when the tunnel dropped comes back on exactly the same tick.
     */
    const val PING_JITTER = 0.2

    /**
     * Peers probed and drained at once. The scheduler is these workers plus one timer and nothing
     * else, however many peers hold a queue — which is why a thousand silent contacts cost no
     * coroutines at all.
     */
    const val DRAIN_WORKERS = 4

    /**
     * A frame from a peer newer than this is proof enough that it is there, so the drain skips its
     * own probe. It is what makes a reply typed into an open chat leave with no round trip in
     * front of it, and what lets a manual ping turn straight into a send.
     */
    const val PEER_FRESH_MS = 10_000L

    /** Actions read per peer per drain pass: the cap on one peer's queue walk. */
    const val PEER_DRAIN_PAGE = 200L

    /** Peers the scheduler tracks at once, oldest work first, so its map cannot grow with the contact list. */
    const val MAX_DRAIN_PEERS = 64L

    /** Re-reads of one peer's queue inside a single drain, so a busy peer cannot hold a worker forever. */
    const val DRAIN_PASSES = 4

    /**
     * How long a peer with work still left over waits before its next pass. Small, because it is
     * answering — but never zero, or a peer whose remaining work cannot run would be rescheduled
     * as fast as the scheduler can loop.
     */
    const val DRAIN_RESUME_MS = 250L

    /** Message ids per seen envelope. A backlog of unread messages is a few frames, never one each. */
    const val SEEN_BATCH = 200L

    /**
     * Actions one peer may have queued before a new one is refused. Nothing else bounds a queue
     * that never gives up, and silently swallowing the two-hundredth message would be worse than
     * saying so.
     */
    const val MAX_PENDING_PER_PEER = 500

    /** Reachability timestamps held in memory; the oldest go first once the map is this big. */
    const val PEER_FRESH_CACHE = 128

    /** The scheduler never sleeps longer than this, so a missed wake-up heals itself. */
    const val SCHEDULER_MAX_SLEEP_MS = 300_000L

    /** The expiry sweep and the traffic flush, which used to ride the outbox cycle. */
    const val HOUSEKEEPING_INTERVAL_MS = 60_000L

    /**
     * Above this the sender asks first and waits for an answer instead of simply streaming. There
     * is no upper limit any more; this is the point where a file is big enough that it should be
     * the receiver's decision.
     */
    const val ATT_OFFER_THRESHOLD_BYTES = 5L * 1024 * 1024

    /**
     * The largest attachment accepted with no prior offer. This is the old size cap with a new
     * job: a peer too old to understand an offer only ever sends below it, so everything under
     * this behaves exactly as it did before.
     */
    const val ATT_AUTO_ACCEPT_BYTES = 16L * 1024 * 1024

    /** Not a product limit — a guard against a nonsense size or chunk count from the wire. */
    const val ATT_SANITY_MAX_BYTES = 64L * 1024 * 1024 * 1024

    /** Headroom kept free when deciding whether a file fits, so accepting one cannot fill the device. */
    const val ATT_SPACE_RESERVE = 64L * 1024 * 1024

    /** Reassemblies in flight at once: each costs a file handle and a little metadata. */
    const val MAX_INCOMING_TRANSFERS = 8

    /** per-connection outbound queue (frames); bounds the memory one peer can cost */
    const val PEER_QUEUE_CAP = 16

    /** the largest legacy inline base64 attachment still sent in one frame, keeping it under the frame cap */
    const val MAX_INLINE_B64 = 180_000

    /** Legacy inline attachments converted to files per pass, so the migration never reads the table whole. */
    const val LEGACY_ATTACHMENT_BATCH = 25L

    /** chat and summary invalidations and transfer progress flush at most this often */
    const val EVENT_FLUSH_MS = 50L

    /** transfer progress is only reported in steps of at least this fraction */
    const val PROGRESS_STEP = 0.02

    const val READ_BUFFER_BYTES = 16 * 1024

    /** A write that has not finished by then is to a peer that stopped reading; the socket is closed to free the writer. */
    const val WRITE_TIMEOUT_MS = 30_000L

    /** Ten years. Above this `ts + secs * 1000` stops being a date and starts being an overflow. */
    const val MAX_EXPIRE_SECS = 315_360_000L

    /** Ids one seen envelope may carry; ten times what we ever send, so a peer cannot cost thousands of updates per frame. */
    const val MAX_SEEN_IDS = 2_000

    /** Changed rows one chat announcement names before a plain re-read is cheaper than the id list. */
    const val MAX_DELTA_IDS = 200

    /** A cover stands in for one bubble and one notification line; a peer cannot make it a payload. */
    const val MAX_COVER_CHARS = 120
}

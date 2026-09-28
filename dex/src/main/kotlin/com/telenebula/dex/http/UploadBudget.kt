package com.telenebula.dex.http

import com.telenebula.dex.Limits
import java.util.concurrent.atomic.AtomicBoolean

/** Uploads in flight and the disk they stage, across every browser and per login; a ticket is held for exactly one upload. */
class UploadBudget(
    private val maxUploads: Int = Limits.MAX_UPLOADS,
    private val maxPerSession: Int = Limits.MAX_UPLOADS_PER_SESSION,
    private val maxBytes: Long = Limits.MAX_STAGING_BYTES,
) {
    private val lock = Any()
    private var uploads = 0
    private var bytes = 0L
    private val bySession = HashMap<String, Int>()

    inner class Ticket internal constructor(private val session: String, private val size: Long) : AutoCloseable {
        private val isClosed = AtomicBoolean(false)

        override fun close() {
            if (!isClosed.compareAndSet(false, true)) return
            synchronized(lock) {
                uploads -= 1
                bytes -= size
                val left = (bySession[session] ?: 1) - 1
                if (left <= 0) bySession.remove(session) else bySession[session] = left
            }
        }
    }

    /** null when this upload would exceed any of the three bounds. */
    fun reserve(session: String, size: Long): Ticket? = synchronized(lock) {
        val mine = bySession[session] ?: 0
        if (uploads >= maxUploads || mine >= maxPerSession || bytes + size > maxBytes) return null
        uploads += 1
        bytes += size
        bySession[session] = mine + 1
        Ticket(session, size)
    }

    val stagedBytes: Long get() = synchronized(lock) { bytes }
}

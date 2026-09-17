package com.telenebula.core.db

import java.util.concurrent.locks.ReentrantLock

/** The `attachment_transfers` table: an offer, and later the transfer it becomes. */
internal class TransfersDao(db: SqlDb, lock: ReentrantLock) : Dao(db, lock) {
    /**
     * An offer, and later the transfer it becomes. This is what lets a large file wait
     * indefinitely for an answer: the in-flight map only holds transfers that are actively
     * streaming, while the decision lives here.
     */
    fun upsertTransfer(transferId: String, peerIp: String, isIncoming: Boolean, state: Wire.TransferState, size: Long) = lockedTransaction {
        val stamp = now()
        db.insert(
            """
            INSERT OR IGNORE INTO attachment_transfers
              (transfer_id, peer_ip, direction, state, size, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(transferId, peerIp, if (isIncoming) "in" else "out", state.wire, size, stamp, stamp),
        )
        db.update(
            "UPDATE attachment_transfers SET state = ?, updated_at = ? WHERE transfer_id = ?",
            listOf(state.wire, stamp, transferId),
        )
        Unit
    }

    fun setTransferState(transferId: String, state: Wire.TransferState, reason: String? = null) = locked {
        db.update(
            "UPDATE attachment_transfers SET state = ?, reason = ?, updated_at = ? WHERE transfer_id = ?",
            listOf(state.wire, reason, now(), transferId),
        )
        Unit
    }

    fun transferState(transferId: String): Wire.TransferState? = locked {
        Wire.TransferState.of(
            db.queryFirst(
                "SELECT state FROM attachment_transfers WHERE transfer_id = ?",
                listOf(transferId),
            ) { it.string(0) },
        )
    }

    /**
     * Whether this side agreed to receive the transfer. The gate on an oversized att-begin:
     * without an accepted row, nobody asked for those bytes.
     */
    fun transferIsAccepted(transferId: String): Boolean =
        transferState(transferId).let { it == Wire.TransferState.ACCEPTED || it == Wire.TransferState.RECEIVING }

    /**
     * Transfers that still expect more bytes. Their partial files are referenced by no message
     * yet, so without this the orphan sweep would delete the progress a resume depends on.
     */
    fun unfinishedTransferIds(): List<String> = locked {
        db.query(
            "SELECT transfer_id FROM attachment_transfers WHERE state IN ('offered','accepted','receiving')",
        ) { it.string(0) }
    }

    /** Open transfers whose message still exists; a chunk for a deleted message is refused, so its partial can never resume. */
    fun resumableTransferIds(): List<String> = locked {
        db.query(
            """
            SELECT t.transfer_id FROM attachment_transfers t
            WHERE t.state IN ('offered','accepted','receiving')
              AND EXISTS (SELECT 1 FROM messages m WHERE m.id = t.transfer_id)
            """.trimIndent(),
        ) { it.string(0) }
    }

    fun abandonedTransferIds(untouchedSince: Long): List<String> = locked {
        db.query(
            "SELECT transfer_id FROM attachment_transfers WHERE state IN ('offered','accepted','receiving') AND updated_at < ?",
            listOf(untouchedSince),
        ) { it.string(0) }
    }

    /** Who a transfer belongs to, so a frame about it can be checked against its sender. */
    fun transferPeer(transferId: String): String? = locked {
        db.queryFirst(
            "SELECT peer_ip FROM attachment_transfers WHERE transfer_id = ?",
            listOf(transferId),
        ) { it.string(0) }
    }

    fun transferReceived(transferId: String): Long = locked {
        db.queryFirst(
            "SELECT received_chunks FROM attachment_transfers WHERE transfer_id = ?",
            listOf(transferId),
        ) { it.long(0) } ?: 0
    }

    fun setTransferReceived(transferId: String, chunks: Long) = locked {
        db.update(
            "UPDATE attachment_transfers SET received_chunks = ?, updated_at = ? WHERE transfer_id = ?",
            listOf(chunks, now(), transferId),
        )
        Unit
    }

    /** Offers still waiting for an answer, in one direction, for one peer. */
    fun pendingTransfersForPeer(peerIp: String, isIncoming: Boolean): List<String> = locked {
        db.query(
            "SELECT transfer_id FROM attachment_transfers WHERE peer_ip = ? AND direction = ? AND state = 'offered'",
            listOf(peerIp, if (isIncoming) "in" else "out"),
        ) { it.string(0) }
    }
}

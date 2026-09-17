package com.telenebula.core.db

import com.telenebula.core.CoreJson
import com.telenebula.core.CorePaths
import com.telenebula.core.db.Wire.wire
import com.telenebula.core.model.CallLog
import com.telenebula.core.model.PeerStats
import java.util.concurrent.locks.ReentrantLock

/** Traffic counters, message statistics and the call log. */
internal class StatsDao(db: SqlDb, lock: ReentrantLock) : Dao(db, lock) {
    fun addTraffic(ip: String, sent: Long, received: Long) = lockedTransaction { addTrafficNow(ip, sent, received) }

    /** The untransacted body, for a caller already inside a transaction. */
    fun addTrafficNow(ip: String, sent: Long, received: Long) {
        db.insert(
            "INSERT OR IGNORE INTO peer_traffic (peer_ip, bytes_sent, bytes_received) VALUES (?, 0, 0)",
            listOf(ip),
        )
        db.update(
            "UPDATE peer_traffic SET bytes_sent = bytes_sent + ?, bytes_received = bytes_received + ? WHERE peer_ip = ?",
            listOf(sent, received, ip),
        )
    }

    fun totalTraffic(): Pair<Long, Long> = locked {
        db.queryFirst(
            "SELECT COALESCE(SUM(bytes_sent), 0), COALESCE(SUM(bytes_received), 0) FROM peer_traffic",
        ) { it.long(0) to it.long(1) } ?: (0L to 0L)
    }

    /** The message and traffic half of a peer's statistics; the action counts come from the outbox. */
    fun messageStats(peerIp: String): PeerStats = locked {
        val counts = db.queryFirst(
            """
            SELECT
              COALESCE(SUM(CASE WHEN direction = 'out' THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN direction = 'in' THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN direction = 'out' AND kind != 'text' THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN direction = 'in' AND kind != 'text' THEN 1 ELSE 0 END), 0),
              MIN(ts), MAX(ts)
            FROM messages WHERE peer_ip = ?
            """.trimIndent(),
            listOf(peerIp),
        ) { row ->
            PeerStats(
                messagesSent = row.int(0),
                messagesReceived = row.int(1),
                mediaSent = row.int(2),
                mediaReceived = row.int(3),
                firstMessageAt = row.longOrNull(4),
                lastActivityAt = row.longOrNull(5),
            )
        } ?: PeerStats()
        val traffic = db.queryFirst(
            "SELECT bytes_sent, bytes_received FROM peer_traffic WHERE peer_ip = ?",
            listOf(peerIp),
        ) { it.long(0) to it.long(1) } ?: (0L to 0L)
        counts.copy(bytesSent = traffic.first, bytesReceived = traffic.second)
    }

    // --- call logs ---

    fun insertCallLog(log: CallLog) = locked {
        db.insert(
            """
            INSERT OR REPLACE INTO call_logs
              (id, peer_ip, direction, is_video, outcome, started_at, connected_at, ended_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                log.id,
                log.peerIp,
                log.direction.wire,
                log.isVideo,
                log.outcome.wire,
                log.startedAt,
                log.connectedAt,
                log.endedAt,
            ),
        )
        Unit
    }

    fun deleteCallLogs(ids: List<String>) = lockedTransaction {
        for (chunk in ids.chunked(MessagesDao.CHUNK)) {
            db.update("DELETE FROM call_logs WHERE id IN (${placeholders(chunk.size)})", chunk)
        }
    }

    /** The newest calls across every peer (the Calls tab). */
    fun getAllCallLogs(limit: Long): List<CallLog> = locked {
        db.query("SELECT $CALL_COLS FROM call_logs ORDER BY started_at DESC LIMIT ?", listOf(limit), ::toCallLog)
    }

    fun getCallLogs(peerIp: String, limit: Long): List<CallLog> = locked {
        db.query(
            "SELECT $CALL_COLS FROM call_logs WHERE peer_ip = ? ORDER BY started_at DESC LIMIT ?",
            listOf(peerIp, limit),
            ::toCallLog,
        )
    }
}

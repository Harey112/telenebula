package com.telenebula.core.db

import com.telenebula.core.CoreJson
import com.telenebula.core.CorePaths
import com.telenebula.core.db.Wire.wire
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionPayload
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageActionType
import java.util.concurrent.locks.ReentrantLock

/** The outbox table. */
internal class ActionsDao(db: SqlDb, lock: ReentrantLock) : Dao(db, lock) {
    /**
     * Queues an action. `created_at` is forced past every action already queued for that message:
     * the drain orders on it, and a clock that steps backwards would otherwise let a new action
     * overtake one it must follow. `max_attempts` keeps its inherited default and means nothing.
     */
    fun createAction(action: MessageAction) = lockedTransaction { createActionNow(action) }

    private fun createActionNow(action: MessageAction) {
        val newest = db.queryFirst(
            "SELECT max(created_at) FROM message_actions WHERE message_id = ?",
            listOf(action.messageId),
        ) { it.longOrNull(0) } ?: 0L
        val createdAt = maxOf(action.createdAt, newest + 1)
        db.insert(
            """
            INSERT OR IGNORE INTO message_actions
              (id, message_id, peer_ip, type, payload_json, status, attempts, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                action.id,
                action.messageId,
                action.peerIp,
                action.type.wire,
                CoreJson.encodeToString(MessageActionPayload.serializer(), action.payload),
                action.status.wire,
                action.attempts,
                createdAt,
                action.updatedAt,
            ),
        )
    }

    fun getAction(id: String): MessageAction? = locked {
        db.queryFirst("SELECT $ACTION_COLS FROM message_actions WHERE id = ?", listOf(id), ::toAction)
    }

    fun getActionsForMessage(messageId: String): List<MessageAction> = locked {
        db.query(
            "SELECT $ACTION_COLS FROM message_actions WHERE message_id = ? ORDER BY created_at ASC, rowid ASC",
            listOf(messageId),
            ::toAction,
        )
    }

    /** One read, so the frame that carries a batch settles every row it covered without a lookup each. */
    fun pendingSeenActionsFor(messageIds: List<String>): List<MessageAction> = locked {
        if (messageIds.isEmpty()) return@locked emptyList()
        val out = ArrayList<MessageAction>(messageIds.size)
        for (chunk in messageIds.chunked(MessagesDao.CHUNK)) {
            out += db.query(
                """
                SELECT $ACTION_COLS FROM message_actions
                WHERE type = 'seen' AND status = 'pending'
                  AND message_id IN (${placeholders(chunk.size)})
                ORDER BY created_at ASC, rowid ASC
                """.trimIndent(),
                chunk,
                ::toAction,
            )
        }
        out
    }

    /**
     * One peer with open work, as the scheduler needs it: who, how much, and how old the oldest
     * item is. This single read yields the peer set, the queue depths the UI shows and the fairness
     * order all at once — the drain never asks per row.
     *
     * `waiting` counts as open on purpose: a peer whose whole queue is parked on unanswered offers
     * would otherwise never be probed and its offers never renewed. A contact that is blocked is
     * excluded; one that is not a contact at all is not blocked, so it stays.
     */
    fun peersWithOpenActions(limit: Long): List<PeerQueueRow> = locked {
        db.query(
            """
            SELECT a.peer_ip, COUNT(*), MIN(a.created_at)
            FROM message_actions a
            LEFT JOIN contacts c ON c.ip = a.peer_ip
            WHERE a.status IN ('pending','waiting')
              AND a.peer_ip != ''
              AND (c.is_blocked IS NULL OR c.is_blocked = 0)
            GROUP BY a.peer_ip
            ORDER BY MIN(a.created_at) ASC
            LIMIT ?
            """.trimIndent(),
            listOf(limit),
        ) { PeerQueueRow(it.string(0), it.int(1), it.long(2)) }
    }

    /** One peer's runnable queue, oldest first and capped: the whole input to one drain pass. */
    fun pendingActionsForPeer(peerIp: String, limit: Long): List<MessageAction> = locked {
        db.query(
            """
            SELECT $ACTION_COLS FROM message_actions
            WHERE peer_ip = ? AND status = 'pending'
            ORDER BY created_at ASC, rowid ASC LIMIT ?
            """.trimIndent(),
            listOf(peerIp, limit),
            ::toAction,
        )
    }

    /** How many actions are still open for a peer, for the queue-state event. */
    fun openActionCount(peerIp: String): Int = locked {
        db.queryFirst(
            "SELECT COUNT(*) FROM message_actions WHERE peer_ip = ? AND status IN ('pending','waiting')",
            listOf(peerIp),
        ) { it.int(0) } ?: 0
    }

    /** True once a peer's queue is at the cap, so a new send is refused rather than silently piling up. */
    fun pendingActionCount(peerIp: String): Int = locked {
        db.queryFirst(
            "SELECT COUNT(*) FROM message_actions WHERE peer_ip = ? AND status = 'pending'",
            listOf(peerIp),
        ) { it.int(0) } ?: 0
    }

    fun setActionPayload(id: String, payload: MessageActionPayload) = locked {
        db.update(
            "UPDATE message_actions SET payload_json = ?, updated_at = ? WHERE id = ?",
            listOf(CoreJson.encodeToString(MessageActionPayload.serializer(), payload), now(), id),
        )
        Unit
    }

    /** The single open action of a type for a message, when there is exactly one. */
    fun openActionOf(messageId: String, type: MessageActionType): MessageAction? = locked {
        db.queryFirst(
            """
            SELECT $ACTION_COLS FROM message_actions
            WHERE message_id = ? AND type = ? AND status = 'pending'
            ORDER BY created_at ASC, rowid ASC
            """.trimIndent(),
            listOf(messageId, type.wire),
            ::toAction,
        )
    }

    fun setActionStatus(id: String, status: MessageActionStatus) = locked {
        db.update("UPDATE message_actions SET status = ?, updated_at = ? WHERE id = ?", listOf(status.wire, now(), id))
        Unit
    }

    /**
     * Ends an action and records why, so the bubble can say what happened instead of a bare
     * "not sent". The status separates the two kinds of ending: `failed` is something that went
     * wrong, `cancelled` is a choice somebody made.
     */
    fun setActionOutcome(id: String, status: MessageActionStatus, reason: String) = lockedTransaction {
        val payload = db.queryFirst(
            "SELECT payload_json FROM message_actions WHERE id = ?",
            listOf(id),
        ) { decodePayload(it.stringOrNull(0)) } ?: MessageActionPayload()
        db.update(
            "UPDATE message_actions SET status = ?, payload_json = ?, updated_at = ? WHERE id = ?",
            listOf(
                status.wire,
                CoreJson.encodeToString(MessageActionPayload.serializer(), payload.copy(failReason = reason)),
                now(),
                id,
            ),
        )
        Unit
    }

    /** Puts a failed action back in the queue with a fresh attempt budget. */
    fun resetActionForRetry(id: String) = locked {
        db.update(
            "UPDATE message_actions SET status = 'pending', updated_at = ? WHERE id = ?",
            listOf(now(), id),
        )
        Unit
    }

    /** Creates send actions for legacy pending messages that predate the outbox. */
    fun backfillSendActions() = lockedTransaction {
        val rows = db.query(
            """
            SELECT m.id, m.peer_ip FROM messages m
            WHERE m.direction = 'out' AND m.status = 'pending'
              AND NOT EXISTS (SELECT 1 FROM message_actions a WHERE a.message_id = m.id AND a.type = 'send')
            """.trimIndent(),
        ) { it.string(0) to it.string(1) }
        val stamp = now()
        for ((id, peerIp) in rows) {
            createActionNow(
                MessageAction(
                    id = id,
                    messageId = id,
                    peerIp = peerIp,
                    type = MessageActionType.SEND,
                    status = MessageActionStatus.PENDING,
                    attempts = 0,
                    createdAt = stamp,
                    updatedAt = stamp,
                ),
            )
        }
    }

    /** Ids of failed actions (optionally for one peer), oldest first and capped. */
    fun failedActionIds(peerIp: String?, limit: Long = RETRY_PAGE): List<String> = locked {
        db.query(
            "SELECT id FROM message_actions WHERE status = 'failed' AND (? IS NULL OR peer_ip = ?) ORDER BY created_at ASC, rowid ASC LIMIT ?",
            listOf(peerIp, peerIp, limit),
        ) { it.string(0) }
    }

    /** (pending, failed) action counts, for one peer or everywhere. */
    fun actionCounts(peerIp: String?): Pair<Int, Int> = locked {
        db.queryFirst(
            """
            SELECT
              COALESCE(SUM(CASE WHEN status = 'pending' THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END), 0)
            FROM message_actions WHERE (? IS NULL OR peer_ip = ?)
            """.trimIndent(),
            listOf(peerIp, peerIp),
        ) { it.int(0) to it.int(1) } ?: (0 to 0)
    }

    private companion object {
        /** Failed actions one "retry everything" re-queues; the rest stay failed until it is pressed again. */
        const val RETRY_PAGE = 500L
    }
}

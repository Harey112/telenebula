package com.telenebula.core.db

import com.telenebula.core.CoreJson
import com.telenebula.core.CorePaths
import com.telenebula.core.db.Wire.wire
import com.telenebula.core.model.ChatLink
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.ChatSummary
import com.telenebula.core.model.ChatView
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageAttachment
import com.telenebula.core.model.Contact
import com.telenebula.core.model.MessageCursor
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus
import java.util.concurrent.locks.ReentrantLock

/** Messages, their reactions and receipts, and the coarse views a screen renders from one read. */
internal class MessagesDao(db: SqlDb, lock: ReentrantLock, private val contacts: ContactsDao) : Dao(db, lock) {
    /**
     * Stores a message; false when one with that id was already there. The caller needs to know:
     * a queue that re-sends until acked will redeliver a message whose ack was lost, and only a
     * real insert may raise a notification.
     */
    fun insertMessage(message: ChatMessage): Boolean = locked {
        db.insert(
            """
            INSERT OR IGNORE INTO messages
              (id, peer_ip, direction, body, ts, status, read, kind, attachment_json, edited, deleted,
               reactions_json, reply_to_id, seen_at, expire_secs, expires_at, covered)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                message.id,
                message.peerIp,
                message.direction.wire,
                message.body,
                message.ts,
                message.status.wire,
                message.direction == MessageDirection.OUT,
                message.kind.wire,
                message.attachment?.let { CoreJson.encodeToString(MessageAttachment.serializer(), it) },
                message.isEdited,
                message.isDeleted,
                encodeReactions(message.reactions),
                message.replyToId,
                message.seenAt,
                message.expireSecs,
                message.expiresAt,
                message.isCovered,
            ),
        )
    }

    fun setMessageStatus(id: String, status: MessageStatus) = locked {
        db.update("UPDATE messages SET status = ? WHERE id = ?", listOf(status.wire, id))
        Unit
    }

    fun getMessage(id: String): ChatMessage? = locked {
        db.queryFirst("SELECT $MSG_COLS FROM messages WHERE id = ?", listOf(id), ::toMessage)
    }

    fun getMessages(peerIp: String, limit: Long): List<ChatMessage> = getMessagesFrom(peerIp, null, limit)

    /** Without [from], the newest [limit]; with it, the oldest [limit] at or after it, so a far-back window keeps its old end. */
    private fun getMessagesFrom(peerIp: String, from: MessageCursor?, limit: Long): List<ChatMessage> = locked {
        if (from == null) {
            db.query(
                """
                SELECT $MSG_COLS FROM (
                  SELECT * FROM messages WHERE peer_ip = ? ORDER BY ts DESC, id DESC LIMIT ?
                ) ORDER BY ts ASC, id ASC
                """.trimIndent(),
                listOf(peerIp, limit),
                ::toMessage,
            )
        } else {
            db.query(
                """
                SELECT $MSG_COLS FROM messages
                WHERE peer_ip = ? AND (ts > ? OR (ts = ? AND id >= ?))
                ORDER BY ts ASC, id ASC LIMIT ?
                """.trimIndent(),
                listOf(peerIp, from.ts, from.ts, from.id, limit),
                ::toMessage,
            )
        }
    }

    /** The [limit] messages just before [before], ascending; fewer than [limit] means the beginning was reached. */
    fun getMessagesBefore(peerIp: String, before: MessageCursor, limit: Long): List<ChatMessage> = locked {
        db.query(
            """
            SELECT $MSG_COLS FROM (
              SELECT * FROM messages
              WHERE peer_ip = ? AND (ts < ? OR (ts = ? AND id < ?))
              ORDER BY ts DESC, id DESC LIMIT ?
            ) ORDER BY ts ASC, id ASC
            """.trimIndent(),
            listOf(peerIp, before.ts, before.ts, before.id, limit),
            ::toMessage,
        )
    }

    fun markChatRead(peerIp: String) = lockedTransaction {
        // a disappearing timer on an incoming message starts at first read
        db.update(
            """
            UPDATE messages SET expires_at = ? + expire_secs * 1000
            WHERE peer_ip = ? AND direction = 'in' AND read = 0
              AND status != 'receiving'
              AND expire_secs IS NOT NULL AND expires_at IS NULL
            """.trimIndent(),
            listOf(now(), peerIp),
        )
        db.update("UPDATE messages SET read = 1 WHERE peer_ip = ? AND direction = 'in'", listOf(peerIp))
        db.update("UPDATE contacts SET is_marked_unread = 0 WHERE ip = ?", listOf(peerIp))
        Unit
    }

    /** Case-insensitive substring search in one chat, newest first. */
    fun searchMessages(peerIp: String, query: String, limit: Long): List<ChatMessage> = locked {
        val pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        db.query(
            """
            SELECT $MSG_COLS FROM messages
            WHERE peer_ip = ? AND deleted = 0
              AND (body LIKE ? ESCAPE '\' OR attachment_json LIKE ? ESCAPE '\')
            ORDER BY ts DESC LIMIT ?
            """.trimIndent(),
            listOf(peerIp, pattern, pattern, limit),
            ::toMessage,
        )
    }

    /** The whole chat, oldest first (capped), for export. */
    fun allMessages(peerIp: String, limit: Long): List<ChatMessage> = locked {
        db.query(
            "SELECT $MSG_COLS FROM messages WHERE peer_ip = ? ORDER BY ts ASC LIMIT ?",
            listOf(peerIp, limit),
            ::toMessage,
        )
    }

    /**
     * Sets one user's reaction. Absolute, so redelivery is harmless — and the return value says
     * whether anything actually changed, not merely whether the write was legal. A queue that
     * re-sends until acked would otherwise re-notify for the same emoji on every drain.
     */
    fun setReaction(messageId: String, byIp: String, emoji: String): Boolean = locked {
        val message = getMessage(messageId) ?: return@locked false
        if (message.isDeleted) return@locked false
        if (message.reactions[byIp] == emoji) return@locked false
        writeReactions(messageId, message.reactions + (byIp to emoji))
        true
    }

    /** Removes one user's reaction (absolute, so redelivery is harmless). */
    fun removeReaction(messageId: String, byIp: String): Boolean = locked {
        val message = getMessage(messageId) ?: return@locked false
        writeReactions(messageId, message.reactions - byIp)
        true
    }

    /** Applies an edit; when [fromIp] is given only that author's own message is editable. */
    fun applyEdit(messageId: String, newBody: String, fromIp: String?): Boolean = locked {
        val message = getMessage(messageId) ?: return@locked false
        if (message.isDeleted || message.kind != MessageKind.TEXT) return@locked false
        if (fromIp != null && !(message.direction == MessageDirection.IN && message.peerIp == fromIp)) {
            return@locked false
        }
        // an edit is new content: the peer's edit must be reported as seen again, and our own edit
        // is unseen until the peer reports it
        val sql = if (fromIp != null) {
            "UPDATE messages SET body = ?, edited = 1, seen_reported = 0 WHERE id = ?"
        } else {
            "UPDATE messages SET body = ?, edited = 1, seen_at = NULL WHERE id = ?"
        }
        // "AND body != ?" is what keeps a redelivered edit idempotent: without it every duplicate
        // clears seen_reported and mints a fresh seen action, which grows the queue from the wire
        val changed = db.update("$sql AND body != ?", listOf(newBody, messageId, newBody))
        changed > 0
    }

    // --- seen receipts ---

    /**
     * Incoming messages the user has read whose seen report has not gone yet. Reading and flagging
     * are separate so one queued action can carry many ids: the flag is the source of truth and is
     * only set once the peer acked, which is what makes the receipt survive a restart mid-queue.
     */
    fun unreportedSeenIds(peerIp: String, limit: Long): List<String> = locked {
        db.query(
            """
            SELECT id FROM messages
            WHERE peer_ip = ? AND direction = 'in' AND read = 1 AND seen_reported = 0
              AND status NOT IN ('offered','receiving')
            ORDER BY ts ASC LIMIT ?
            """.trimIndent(),
            listOf(peerIp, limit),
        ) { it.string(0) }
    }

    /** Re-opens one message's receipt, so a retried row has something left to report. */
    fun markSeenUnreported(id: String) = locked {
        db.update("UPDATE messages SET seen_reported = 0 WHERE id = ? AND direction = 'in'", listOf(id))
        Unit
    }

    fun markSeenReported(ids: List<String>) = lockedTransaction {
        for (chunk in ids.chunked(CHUNK)) {
            db.update(
                "UPDATE messages SET seen_reported = 1 WHERE id IN (${placeholders(chunk.size)})",
                chunk,
            )
        }
    }

    /**
     * The peer reports having seen our messages; only unseen outgoing ones to that same peer are
     * stamped, so redelivery is harmless.
     */
    fun setSeenByPeer(ids: List<String>, peerIp: String): Boolean = lockedTransaction {
        if (ids.isEmpty()) return@lockedTransaction false
        var changed = 0
        for (chunk in ids.chunked(CHUNK)) {
            changed += db.update(
                """
                UPDATE messages SET seen_at = ?
                WHERE peer_ip = ? AND direction = 'out' AND seen_at IS NULL
                  AND id IN (${placeholders(chunk.size)})
                """.trimIndent(),
                listOf(now(), peerIp) + chunk,
            )
        }
        changed > 0
    }

    /** Tombstones a message (delete for everyone) and wipes its content. */
    fun applyDeleteForEveryone(messageId: String, fromIp: String?): Boolean = locked {
        val message = getMessage(messageId) ?: return@locked false
        if (fromIp != null && !(message.direction == MessageDirection.IN && message.peerIp == fromIp)) {
            return@locked false
        }
        db.update(
            "UPDATE messages SET deleted = 1, body = '', attachment_json = NULL, reactions_json = '{}' WHERE id = ?",
            listOf(messageId),
        )
        true
    }

    /** Marks a message deleted but keeps its content, so a cancelled delete can restore it. */
    fun markDeleted(messageId: String) = locked {
        db.update("UPDATE messages SET deleted = 1 WHERE id = ?", listOf(messageId))
        Unit
    }

    fun unmarkDeleted(messageId: String) = locked {
        db.update("UPDATE messages SET deleted = 0 WHERE id = ?", listOf(messageId))
        Unit
    }

    fun wipeDeletedContent(messageId: String) = locked {
        db.update(
            "UPDATE messages SET body = '', attachment_json = NULL, reactions_json = '{}', covered = 0 WHERE id = ? AND deleted = 1",
            listOf(messageId),
        )
        Unit
    }

    /** Restores body and edited state, reverting a cancelled edit action. */
    fun restoreBody(messageId: String, body: String, isEdited: Boolean) = locked {
        db.update("UPDATE messages SET body = ?, edited = ? WHERE id = ?", listOf(body, isEdited, messageId))
        Unit
    }

    /** Removes the message locally only. Its action history goes with it. */
    fun deleteMessageForMe(messageId: String) = lockedTransaction {
        db.update("DELETE FROM message_actions WHERE message_id = ?", listOf(messageId))
        db.update("DELETE FROM messages WHERE id = ?", listOf(messageId))
        // an open transfer for a message that is gone can never finish; left open it would pin its partial file
        db.update(
            "UPDATE attachment_transfers SET state = 'cancelled', updated_at = ? WHERE transfer_id = ? AND state IN ('offered','accepted','receiving')",
            listOf(now(), messageId),
        )
        Unit
    }

    fun getUnreadTotal(): Int = locked {
        db.count("SELECT COUNT(*) FROM messages WHERE direction = 'in' AND read = 0").toInt()
    }

    // --- attachments (legacy migration) ---

    /** Rows still carrying a pre-file-storage inline base64 attachment. */
    fun getLegacyInlineAttachmentIds(limit: Long): List<String> = locked {
        db.query(
            "SELECT id FROM messages WHERE attachment_json LIKE '%\"dataB64\"%' LIMIT ?",
            listOf(limit),
        ) { it.string(0) }
    }

    fun clearMessageAttachment(id: String) = locked {
        db.update("UPDATE messages SET attachment_json = NULL WHERE id = ?", listOf(id))
        Unit
    }

    fun setMessageAttachment(id: String, attachment: MessageAttachment) = locked {
        db.update(
            "UPDATE messages SET attachment_json = ? WHERE id = ?",
            listOf(CoreJson.encodeToString(MessageAttachment.serializer(), attachment), id),
        )
        Unit
    }

    // --- coarse view models ---

    /** Messages of one chat that carry an attachment, newest first (chat settings → Media). */
    fun getChatMedia(peerIp: String, limit: Long): List<ChatMessage> = locked {
        db.query(
            """
            SELECT $MSG_COLS FROM messages
            WHERE peer_ip = ? AND deleted = 0 AND attachment_json IS NOT NULL
              AND status NOT IN ('offered','receiving','declined','cancelled')
            ORDER BY ts DESC LIMIT ?
            """.trimIndent(),
            listOf(peerIp, limit),
            ::toMessage,
        )
    }

    /** How many rows [getChatMedia] would return unbounded, for a header that shows a handful and a count. */
    fun countChatMedia(peerIp: String): Int = locked {
        db.count(
            """
            SELECT COUNT(*) FROM messages
            WHERE peer_ip = ? AND deleted = 0 AND attachment_json IS NOT NULL
              AND status NOT IN ('offered','receiving','declined','cancelled')
            """.trimIndent(),
            listOf(peerIp),
        ).toInt()
    }

    /** URLs found in one chat's message bodies, newest first (chat settings → Links). */
    fun getChatLinks(peerIp: String, limit: Long): List<ChatLink> = locked {
        db.query(
            """
            SELECT id, body, ts FROM messages
            WHERE peer_ip = ? AND deleted = 0 AND (body LIKE '%http://%' OR body LIKE '%https://%')
            ORDER BY ts DESC LIMIT ?
            """.trimIndent(),
            listOf(peerIp, limit),
        ) { Triple(it.string(0), it.string(1), it.long(2)) }
            .flatMap { (id, body, ts) -> extractUrls(body).map { ChatLink(messageId = id, url = it, ts = ts) } }
    }

    /** Messages in one chat that carry a URL; the count a header shows next to a few of them. */
    fun countChatLinkMessages(peerIp: String): Int = locked {
        db.count(
            "SELECT COUNT(*) FROM messages WHERE peer_ip = ? AND deleted = 0 AND (body LIKE '%http://%' OR body LIKE '%https://%')",
            listOf(peerIp),
        ).toInt()
    }

    fun getChatSummaries(): List<ChatSummary> = locked {
        db.query(
            """
            SELECT
              c.ip,
              CASE
                WHEN c.nickname IS NOT NULL AND c.nickname != '' THEN c.nickname
                WHEN c.name != '' THEN c.name
                ELSE c.ip
              END AS name,
              CASE
                WHEN m.deleted = 1 THEN 'Message deleted'
                WHEN m.covered = 1 THEN 'Covered message'
                WHEN m.kind = 'image' THEN 'Photo'
                WHEN m.kind = 'video' THEN 'Video'
                WHEN m.kind = 'file' AND m.attachment_json LIKE '%"mime":"audio/%' THEN 'Voice message'
                WHEN m.kind = 'file' THEN 'File'
                ELSE m.body
              END AS last_body,
              m.ts, m.direction, m.status, m.seen_at,
              COALESCE(agg.unread, 0),
              c.pinned_at, c.is_archived, c.is_blocked, c.mute_until, c.is_marked_unread,
              sa.status AS last_send_status
            FROM contacts c
            LEFT JOIN (
              SELECT peer_ip, MAX(ts) AS max_ts,
                     SUM(CASE WHEN direction = 'in' AND read = 0 THEN 1 ELSE 0 END) AS unread
              FROM messages GROUP BY peer_ip
            ) agg ON agg.peer_ip = c.ip
            LEFT JOIN messages m ON m.peer_ip = c.ip AND m.ts = agg.max_ts
            -- a send action shares its message's id, so this is a primary key lookup
            LEFT JOIN message_actions sa ON sa.id = m.id AND sa.type = 'send'
            ORDER BY (c.pinned_at IS NOT NULL) DESC, c.pinned_at DESC,
                     COALESCE(m.ts, c.added_at) DESC
            """.trimIndent(),
        ) { row ->
            val direction = row.stringOrNull(4)?.let(Wire::direction)
            ChatSummary(
                ip = row.string(0),
                name = row.string(1),
                lastBody = row.stringOrNull(2),
                lastTs = row.longOrNull(3),
                lastDirection = direction,
                lastStatus = row.stringOrNull(5)?.let { Wire.status(it, direction ?: MessageDirection.IN) },
                lastSeenAt = row.longOrNull(6),
                unread = row.int(7),
                pinnedAt = row.longOrNull(8),
                isArchived = row.boolean(9),
                isBlocked = row.boolean(10),
                muteUntil = row.long(11),
                isMarkedUnread = row.boolean(12),
                lastSendStatus = row.stringOrNull(13)?.let(Wire::actionStatus),
            )
        }
    }

    /**
     * Everything the chat screen renders, in one read: the contact, the messages (ascending,
     * capped), their actions and the sources of every reply.
     */
    fun getChatView(peerIp: String, limit: Long): ChatView = getChatView(peerIp, null, limit)

    fun getChatView(peerIp: String, from: MessageCursor?, limit: Long): ChatView = locked {
        assemble(contacts.getContact(peerIp), getMessagesFrom(peerIp, from, limit))
    }

    /** The named rows of one chat with everything the view needs for them, so a reader can patch rather than re-read. */
    fun getChatRows(peerIp: String, ids: Collection<String>): ChatView = locked {
        val rows = ArrayList<ChatMessage>(ids.size)
        for (chunk in ids.distinct().chunked(CHUNK)) {
            rows += db.query(
                "SELECT $MSG_COLS FROM messages WHERE peer_ip = ? AND id IN (${placeholders(chunk.size)})",
                listOf(peerIp) + chunk,
                ::toMessage,
            )
        }
        rows.sortWith(compareBy<ChatMessage> { it.ts }.thenBy { it.id })
        assemble(contacts.getContact(peerIp), rows)
    }

    private fun assemble(contact: Contact?, messages: List<ChatMessage>): ChatView {

        val actions = LinkedHashMap<String, MutableList<MessageAction>>()
        for (chunk in messages.chunked(VIEW_CHUNK)) {
            val rows = db.query(
                """
                SELECT $ACTION_COLS FROM message_actions
                WHERE message_id IN (${placeholders(chunk.size)}) ORDER BY created_at ASC, rowid ASC
                """.trimIndent(),
                chunk.map { it.id },
                ::toAction,
            )
            for (action in rows) actions.getOrPut(action.messageId) { ArrayList() }.add(action)
        }

        val inPage = messages.associateBy { it.id }
        val replySources = LinkedHashMap<String, ChatMessage>()
        val olderThanThePage = LinkedHashSet<String>()
        for (message in messages) {
            val replyId = message.replyToId ?: continue
            if (replySources.containsKey(replyId)) continue
            val source = inPage[replyId]
            if (source != null) replySources[replyId] = source else olderThanThePage += replyId
        }
        for (chunk in olderThanThePage.chunked(VIEW_CHUNK)) {
            val rows = db.query(
                "SELECT $MSG_COLS FROM messages WHERE id IN (${placeholders(chunk.size)})",
                chunk,
                ::toMessage,
            )
            for (source in rows) replySources[source.id] = source
        }

        return ChatView(contact = contact, messages = messages, actions = actions, replySources = replySources)
    }

    private fun writeReactions(messageId: String, reactions: Map<String, String>) {
        db.update("UPDATE messages SET reactions_json = ? WHERE id = ?", listOf(encodeReactions(reactions), messageId))
    }

    companion object {
        const val CHUNK = 200
        private const val VIEW_CHUNK = 100

        /** http(s) URLs in free text, in order, with trailing punctuation trimmed. */
        fun extractUrls(text: String): List<String> {
            val out = ArrayList<String>()
            for (word in text.split(WHITESPACE)) {
                if (word.isEmpty()) continue
                val lower = word.lowercase()
                val https = lower.indexOf("https://")
                val http = lower.indexOf("http://")
                val start = when {
                    https >= 0 && http >= 0 -> minOf(https, http)
                    https >= 0 -> https
                    http >= 0 -> http
                    else -> continue
                }
                val trimmed = word.substring(start).trimEnd(*TRAILING)
                if (trimmed.length > "https://".length && trimmed !in out) out += trimmed
            }
            return out
        }

        private val WHITESPACE = Regex("\\s+")
        private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', '”', '’', '"', '\'', '>', ']')
    }
}

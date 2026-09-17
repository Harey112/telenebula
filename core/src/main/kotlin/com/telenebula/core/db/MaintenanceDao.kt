package com.telenebula.core.db

import com.telenebula.core.CoreException
import com.telenebula.core.CorePaths
import java.io.File
import java.util.concurrent.locks.ReentrantLock

/** Bulk deletes, address moves, the expiry sweep, and the backup snapshot and merge. */
internal class MaintenanceDao(
    db: SqlDb,
    lock: ReentrantLock,
    private val dbPath: String,
    private val openDatabase: (String) -> SqlDb,
    private val stats: StatsDao,
) : Dao(db, lock) {
    fun clearMessages(peerIp: String) = lockedTransaction { clearMessagesNow(peerIp) }

    private fun clearMessagesNow(peerIp: String) {
        db.update(
            "DELETE FROM message_actions WHERE message_id IN (SELECT id FROM messages WHERE peer_ip = ?)",
            listOf(peerIp),
        )
        // a transfer-control action is keyed on its transfer, so no message row leads to it
        db.update("DELETE FROM message_actions WHERE peer_ip = ?", listOf(peerIp))
        db.update("DELETE FROM messages WHERE peer_ip = ?", listOf(peerIp))
        // otherwise the transfer rows outlive their messages, and unfinishedTransferIds keeps
        // pinning their partial files against the orphan sweep for the life of the install
        db.update("DELETE FROM attachment_transfers WHERE peer_ip = ?", listOf(peerIp))
    }

    fun deleteContact(ip: String) = lockedTransaction {
        clearMessagesNow(ip)
        db.update("DELETE FROM call_logs WHERE peer_ip = ?", listOf(ip))
        db.update("DELETE FROM contacts WHERE ip = ?", listOf(ip))
        Unit
    }

    /**
     * Moves a contact to a new overlay address, carrying every record keyed by the old one. If a
     * contact already exists at the new address — the peer wrote first from its new certificate —
     * the two are merged under the new address: the edited contact keeps its nickname, notes and
     * settings, the username announced at the new address wins when known, and both histories stay.
     */
    fun changeContactIp(oldIp: String, newIp: String) = lockedTransaction {
        val exists = db.queryFirst("SELECT 1 FROM contacts WHERE ip = ?", listOf(oldIp)) { it.long(0) } != null
        if (!exists) throw CoreException.internal("no contact at $oldIp")

        val newName = db.queryFirst("SELECT name FROM contacts WHERE ip = ?", listOf(newIp)) { it.string(0) }
        if (newName != null) {
            if (newName.isNotEmpty()) {
                db.update("UPDATE contacts SET name = ? WHERE ip = ?", listOf(newName, oldIp))
            }
            db.update(
                """
                UPDATE contacts SET
                  added_at = min(added_at, (SELECT added_at FROM contacts WHERE ip = ?)),
                  last_seen_at = nullif(max(coalesce(last_seen_at, 0),
                    coalesce((SELECT last_seen_at FROM contacts WHERE ip = ?), 0)), 0)
                WHERE ip = ?
                """.trimIndent(),
                listOf(newIp, newIp, oldIp),
            )
            db.update("DELETE FROM contacts WHERE ip = ?", listOf(newIp))
        }
        db.update("UPDATE contacts SET ip = ? WHERE ip = ?", listOf(newIp, oldIp))
        for (table in listOf("messages", "message_actions", "call_logs", "attachment_transfers")) {
            db.update("UPDATE $table SET peer_ip = ? WHERE peer_ip = ?", listOf(newIp, oldIp))
        }
        val old = db.queryFirst(
            "SELECT bytes_sent, bytes_received FROM peer_traffic WHERE peer_ip = ?",
            listOf(oldIp),
        ) { it.long(0) to it.long(1) }
        if (old != null) {
            stats.addTrafficNow(newIp, old.first, old.second)
            db.update("DELETE FROM peer_traffic WHERE peer_ip = ?", listOf(oldIp))
        }
    }

    /**
     * Deletes the disappearing messages whose timer ran out; returns (peer, attachment path) for
     * each. [inFlight] holds back messages whose delivery is running right now: destroying one
     * mid-send would put on the wire a message this device has already deleted. They expire on the
     * next sweep instead, which is a minute later at worst.
     */
    fun sweepExpired(nowMs: Long, inFlight: Set<String> = emptySet()): List<Pair<String, String?>> = lockedTransaction {
        val rows = db.query(
            "SELECT id, peer_ip, attachment_json FROM messages WHERE expires_at IS NOT NULL AND expires_at <= ?",
            listOf(nowMs),
        ) { Triple(it.string(0), it.string(1), it.stringOrNull(2)) }
        rows.filterNot { it.first in inFlight }.map { (id, peerIp, attachmentJson) ->
            db.update("DELETE FROM message_actions WHERE message_id = ?", listOf(id))
            db.update("DELETE FROM messages WHERE id = ?", listOf(id))
            peerIp to decodeAttachment(attachmentJson)?.uri?.let(CorePaths::uriToPath)
        }
    }

    /** Every attachment path a message still references (no file:// scheme). */
    fun referencedAttachmentPaths(): Set<String> = locked {
        db.query("SELECT attachment_json FROM messages WHERE attachment_json IS NOT NULL") {
            decodeAttachment(it.stringOrNull(0))?.uri?.let(CorePaths::uriToPath)
        }.filterNotNull().toSet()
    }

    fun tableCounts(): Pair<Int, Int> = locked {
        val messages = db.count("SELECT COUNT(*) FROM messages").toInt()
        val contacts = db.count("SELECT COUNT(*) FROM contacts").toInt()
        messages to contacts
    }

    fun clearAllMessages() = lockedTransaction {
        db.update("DELETE FROM message_actions")
        db.update("DELETE FROM messages")
        Unit
    }

    /**
     * A consistent copy of the live database. The write-ahead log is folded back into the main
     * file first, and the store's own lock keeps every writer out for the length of the copy —
     * the same guarantee the single connection gave before.
     */
    fun snapshotTo(destination: String) = locked {
        File(destination).delete()
        db.query<Unit>("PRAGMA wal_checkpoint(TRUNCATE)") { }
        File(dbPath).copyTo(File(destination), overwrite = true)
        Unit
    }

    /**
     * Merges another TeleNebula database into this one: rows whose primary key already exists are
     * left untouched, everything else is added. Pending outbox actions are never imported (they
     * belonged to another device's queue) and attachment paths are rewritten to our directory.
     * Columns are matched by name, so a backup from an older or newer schema still imports what
     * both sides share. Returns (contacts, messages) added.
     */
    fun mergeFrom(sourcePath: String, oldDir: String, newDir: String): Pair<Int, Int> = locked {
        val (messagesBefore, contactsBefore) = tableCounts()
        openDatabase(sourcePath).use { source ->
            db.transaction {
                for (table in MERGED_TABLES) {
                    val theirs = Schema.columnsOf(source, table)
                    if (theirs.isEmpty()) continue
                    // our columns intersected with theirs: the names interpolated below are always ours
                    val shared = Schema.columnsOf(db, table).filter { it in theirs }
                    if (shared.isEmpty()) continue
                    val columns = shared.joinToString(", ")
                    // another device's queue is its own business; only finished actions carry over.
                    // 'waiting' is excluded too: its transfer row does not come across, so it would
                    // arrive parked on an offer nothing can ever renew, stalling its message forever
                    val filter =
                        if (table == "message_actions") " WHERE status NOT IN ('pending','waiting')" else ""
                    val insert = "INSERT OR IGNORE INTO $table ($columns) VALUES (${placeholders(shared.size)})"
                    // a page at a time: a large history must never be materialised whole in memory
                    var offset = 0L
                    while (true) {
                        val rows = source.query("SELECT $columns FROM $table$filter LIMIT $MERGE_PAGE OFFSET $offset") { row ->
                            List(shared.size) { row.value(it) }
                        }
                        for (row in rows) db.insert(insert, row)
                        if (rows.size < MERGE_PAGE) break
                        offset += MERGE_PAGE
                    }
                }
                if (oldDir != newDir && oldDir.isNotEmpty()) {
                    db.update(
                        """
                        UPDATE messages SET attachment_json = replace(attachment_json, ?, ?)
                        WHERE attachment_json IS NOT NULL AND instr(attachment_json, ?) > 0
                        """.trimIndent(),
                        listOf(oldDir, newDir, oldDir),
                    )
                }
            }
        }
        val (messagesAfter, contactsAfter) = tableCounts()
        (contactsAfter - contactsBefore) to (messagesAfter - messagesBefore)
    }

    private companion object {
        const val MERGE_PAGE = 500
        val MERGED_TABLES = listOf("contacts", "messages", "call_logs", "peer_traffic", "message_actions")
    }
}

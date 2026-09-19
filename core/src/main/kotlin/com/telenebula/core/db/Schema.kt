package com.telenebula.core.db

/**
 * The database as it has stood since the React Native build: same file, same tables, same
 * migrations. An install from any earlier version opens here untouched, which is why the DDL
 * below is copied rather than tidied.
 */
internal object Schema {
    fun apply(db: SqlDb) {
        // a PRAGMA that sets a value answers with the value it set, which makes it a query:
        // the framework database refuses to run one through execSQL
        db.query<Unit>("PRAGMA busy_timeout = 5000") { }
        for (statement in TABLES) db.execute(statement)
        migrateMessagesAndContacts(db)
        migrateActionsTable(db)
        db.execute("CREATE INDEX IF NOT EXISTS idx_messages_unread ON messages (peer_ip, direction, read)")
        // keyset paging seeks on (peer_ip, ts, id); the older (peer_ip, ts) index left the id tie-break to a sort
        db.execute("CREATE INDEX IF NOT EXISTS idx_messages_peer_ts_id ON messages (peer_ip, ts, id)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_actions_msg_status ON message_actions (message_id, status)")
        // the scheduler's one coarse read groups by peer over open rows; without this every wake-up
        // would full-scan message_actions
        db.execute("CREATE INDEX IF NOT EXISTS idx_actions_peer_status ON message_actions (peer_ip, status, created_at)")
        // the expiry sweep runs every minute and the unread badge on every summary change: neither may scan the table
        db.execute("CREATE INDEX IF NOT EXISTS idx_messages_expires ON messages (expires_at) WHERE expires_at IS NOT NULL")
        db.execute("CREATE INDEX IF NOT EXISTS idx_messages_direction_read ON messages (direction, read)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_messages_seen_pending ON messages (peer_ip, direction, read, seen_reported)")
        // after the indexes, so the status lookup it runs on every open is an index hit
        cancelOrphanedWaits(db)
    }

    /**
     * The action queue. Written once and used by both the create and the rebuild path, so the two
     * can never drift — a CHECK that disagrees with what this build writes is a crash on insert.
     * `attempts` and `max_attempts` are inherited columns: probes are counted per peer now, so
     * nothing writes them, and SQLite 3.18 cannot drop a column.
     */
    private val ACTIONS_DDL = """
        CREATE TABLE IF NOT EXISTS message_actions (
          id TEXT PRIMARY KEY NOT NULL,
          message_id TEXT NOT NULL,
          peer_ip TEXT NOT NULL,
          type TEXT NOT NULL CHECK (type IN
            ('send','react','edit','delete','seen','att-accept','att-decline','att-cancel','att-error')),
          payload_json TEXT,
          status TEXT NOT NULL CHECK (status IN ('pending','waiting','success','failed','cancelled')),
          attempts INTEGER NOT NULL DEFAULT 0,
          max_attempts INTEGER NOT NULL DEFAULT 10,
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL
        )
    """.trimIndent()

    /** The columns the rebuild carries across; anything else the old table has is dropped. */
    private val ACTION_CARRIED = listOf(
        "id", "message_id", "peer_ip", "type", "payload_json", "attempts", "created_at", "updated_at",
    )

    private val TABLES = listOf(
        """
        CREATE TABLE IF NOT EXISTS contacts (
          ip TEXT PRIMARY KEY NOT NULL,
          name TEXT NOT NULL,
          added_at INTEGER NOT NULL,
          last_seen_at INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS messages (
          id TEXT PRIMARY KEY NOT NULL,
          peer_ip TEXT NOT NULL,
          direction TEXT NOT NULL CHECK (direction IN ('in','out')),
          body TEXT NOT NULL,
          ts INTEGER NOT NULL,
          status TEXT NOT NULL,
          read INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_messages_peer_ts ON messages (peer_ip, ts)",
        "CREATE INDEX IF NOT EXISTS idx_messages_status ON messages (status)",
        ACTIONS_DDL,
        "CREATE INDEX IF NOT EXISTS idx_actions_message ON message_actions (message_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_actions_status ON message_actions (status)",
        """
        CREATE TABLE IF NOT EXISTS call_logs (
          id TEXT PRIMARY KEY NOT NULL,
          peer_ip TEXT NOT NULL,
          direction TEXT NOT NULL CHECK (direction IN ('in','out')),
          is_video INTEGER NOT NULL DEFAULT 0,
          outcome TEXT NOT NULL,
          started_at INTEGER NOT NULL,
          connected_at INTEGER,
          ended_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_call_logs_peer ON call_logs (peer_ip, started_at)",
        """
        CREATE TABLE IF NOT EXISTS peer_traffic (
          peer_ip TEXT PRIMARY KEY NOT NULL,
          bytes_sent INTEGER NOT NULL DEFAULT 0,
          bytes_received INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS attachment_transfers (
          transfer_id TEXT PRIMARY KEY NOT NULL,
          peer_ip TEXT NOT NULL,
          direction TEXT NOT NULL CHECK (direction IN ('in','out')),
          state TEXT NOT NULL CHECK (state IN
            ('offered','accepted','receiving','complete','declined','failed','cancelled')),
          reason TEXT,
          size INTEGER NOT NULL,
          received_chunks INTEGER NOT NULL DEFAULT 0,
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_transfers_peer_state ON attachment_transfers (peer_ip, state)",
    )

    /** Adds the columns installs created before the action pipeline existed do not have. */
    private fun migrateMessagesAndContacts(db: SqlDb) {
        val messageColumns = columnsOf(db, "messages")
        val messageAdditions = listOf(
            "kind" to "kind TEXT NOT NULL DEFAULT 'text'",
            "attachment_json" to "attachment_json TEXT",
            "edited" to "edited INTEGER NOT NULL DEFAULT 0",
            "deleted" to "deleted INTEGER NOT NULL DEFAULT 0",
            "reactions_json" to "reactions_json TEXT",
            "reply_to_id" to "reply_to_id TEXT",
            "seen_at" to "seen_at INTEGER",
            "seen_reported" to "seen_reported INTEGER NOT NULL DEFAULT 0",
            "expire_secs" to "expire_secs INTEGER",
            "expires_at" to "expires_at INTEGER",
            "covered" to "covered INTEGER NOT NULL DEFAULT 0",
        )
        for ((column, ddl) in messageAdditions) {
            if (column !in messageColumns) addColumn(db, "messages", ddl)
        }

        val contactColumns = columnsOf(db, "contacts")
        val contactAdditions = listOf(
            "nickname" to "nickname TEXT NOT NULL DEFAULT ''",
            "notes" to "notes TEXT NOT NULL DEFAULT ''",
            "pinned_at" to "pinned_at INTEGER",
            "is_archived" to "is_archived INTEGER NOT NULL DEFAULT 0",
            "is_blocked" to "is_blocked INTEGER NOT NULL DEFAULT 0",
            "mute_until" to "mute_until INTEGER NOT NULL DEFAULT 0",
            "is_marked_unread" to "is_marked_unread INTEGER NOT NULL DEFAULT 0",
            "notif_json" to "notif_json TEXT",
            "client_version" to "client_version TEXT",
            "disappear_seconds" to "disappear_seconds INTEGER NOT NULL DEFAULT 0",
            "read_receipts" to "read_receipts INTEGER",
            "typing_indicators" to "typing_indicators INTEGER",
            "block_screenshots" to "block_screenshots INTEGER",
            "reveal_gate" to "reveal_gate TEXT",
        )
        for ((column, ddl) in contactAdditions) {
            if (column !in contactColumns) addColumn(db, "contacts", ddl)
        }
    }

    /**
     * Rebuilds message_actions when its CHECK predates a value this build writes. The guard lists
     * one literal per generation of the constraint: miss one and an install skips the rebuild, then
     * violates the CHECK on the first row of that kind. `max_attempts` is deliberately not part of
     * the test — the column stays (3.18 cannot drop one) and simply stops being written.
     */
    private fun migrateActionsTable(db: SqlDb) {
        val sql = db.queryFirst(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'message_actions'",
        ) { it.stringOrNull(0) } ?: return
        val isCurrent = sql.contains("'cancelled'") && sql.contains("'seen'") &&
            sql.contains("'waiting'") && sql.contains("'att-accept'")
        if (isCurrent) return

        // whatever the old table holds, only these come across; a rebuild interrupted last time
        // leaves a half-renamed table that ALTER … RENAME would then fail against forever
        val existing = columnsOf(db, "message_actions")
        val carried = ACTION_CARRIED.filter { it in existing }
        val columns = carried.joinToString(", ")

        db.transaction {
            db.execute("DROP TABLE IF EXISTS message_actions_old")
            db.execute("ALTER TABLE message_actions RENAME TO message_actions_old")
            db.execute(ACTIONS_DDL)
            // Named columns rather than SELECT *: the shapes no longer line up once a column is
            // dropped from the carried set. A 'failed' row with no failReason only ran out of an
            // attempt budget that no longer exists, so it goes back in the queue; one the peer
            // actually refused keeps its verdict, because retrying cannot change a refusal.
            db.execute(
                """
                INSERT INTO message_actions ($columns, status)
                SELECT $columns,
                       CASE WHEN status = 'failed'
                             AND (payload_json IS NULL OR instr(payload_json, 'failReason') = 0)
                            THEN 'pending' ELSE status END
                FROM message_actions_old
                """.trimIndent(),
            )
            db.execute("DROP TABLE message_actions_old")
            db.execute("CREATE INDEX IF NOT EXISTS idx_actions_message ON message_actions (message_id, created_at)")
            db.execute("CREATE INDEX IF NOT EXISTS idx_actions_status ON message_actions (status)")
        }
    }

    /**
     * A parked offer whose transfer row is gone can never be renewed — `renewPendingOffers` reads
     * the transfer table — and a waiting head stalls its message's queue forever. A backup import
     * is how these arise: it carries the action but not the transfer. Cancelling them is the only
     * ending that unblocks the queue.
     */
    private fun cancelOrphanedWaits(db: SqlDb) {
        db.update(
            """
            UPDATE message_actions SET status = 'cancelled'
            WHERE status = 'waiting'
              AND id NOT IN (SELECT transfer_id FROM attachment_transfers)
            """.trimIndent(),
        )
    }

    private fun addColumn(db: SqlDb, table: String, ddl: String) {
        // a concurrent migration may have added it already — that is not a failure
        runCatching { db.execute("ALTER TABLE $table ADD COLUMN $ddl") }
    }

    fun columnsOf(db: SqlDb, table: String, schema: String = "main"): Set<String> =
        db.query("PRAGMA $schema.table_info($table)") { it.string(1) }.toSet()
}

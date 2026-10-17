package com.telenebula.core.db

import com.telenebula.core.CoreJson
import com.telenebula.core.CorePaths
import com.telenebula.core.db.Wire.wire
import com.telenebula.core.model.Contact
import com.telenebula.core.model.ContactFlagsPatch
import com.telenebula.core.model.ContactNotificationPrefs
import com.telenebula.core.model.ContactPrivacyPrefs
import java.util.concurrent.locks.ReentrantLock

internal class ContactsDao(db: SqlDb, lock: ReentrantLock) : Dao(db, lock) {
    fun upsertContact(ip: String, name: String) = lockedTransaction {
        db.insert("INSERT OR IGNORE INTO contacts (ip, name, added_at) VALUES (?, ?, ?)", listOf(ip, name, now()))
        db.update("UPDATE contacts SET name = ?, is_request = 0 WHERE ip = ?", listOf(name, ip))
        Unit
    }

    fun standing(ip: String): Standing = locked {
        when (db.queryFirst("SELECT is_request FROM contacts WHERE ip = ?", listOf(ip)) { it.boolean(0) }) {
            null -> Standing.STRANGER
            true -> Standing.REQUEST
            false -> Standing.CONTACT
        }
    }

    /** A stranger becomes an open request, unless [maxOpenRequests] are already open; a known peer's name is synced. */
    fun admit(ip: String, name: String, maxOpenRequests: Int): Standing = lockedTransaction {
        when (standing(ip)) {
            Standing.STRANGER -> {
                val open = db.count("SELECT COUNT(*) FROM contacts WHERE is_request = 1").toInt()
                if (open >= maxOpenRequests) return@lockedTransaction Standing.STRANGER
                db.insert("INSERT INTO contacts (ip, name, added_at, is_request) VALUES (?, ?, ?, 1)", listOf(ip, name, now()))
                Standing.NEW_REQUEST
            }
            else -> {
                syncContact(ip, name)
                standing(ip)
            }
        }
    }

    /** True when an open request was turned into a contact. */
    fun acceptContact(ip: String): Boolean = locked {
        db.update("UPDATE contacts SET is_request = 0 WHERE ip = ? AND is_request = 1", listOf(ip)) > 0
    }

    /**
     * Adds the contact if missing and keeps its username in sync with what the peer announces (the
     * certificate name is authoritative). An empty announced name never clears a known one, and
     * nicknames are the user's — they are never touched here. True when anything changed.
     */
    fun syncContact(ip: String, name: String): Boolean = locked {
        val inserted = db.insert(
            "INSERT OR IGNORE INTO contacts (ip, name, added_at) VALUES (?, ?, ?)",
            listOf(ip, name, now()),
        )
        if (inserted) return@locked true
        if (name.isEmpty()) return@locked false
        db.update("UPDATE contacts SET name = ? WHERE ip = ? AND name <> ?", listOf(name, ip, name)) > 0
    }

    fun touchContact(ip: String) = locked {
        db.update("UPDATE contacts SET last_seen_at = ? WHERE ip = ?", listOf(now(), ip))
        Unit
    }

    fun setClientVersion(ip: String, version: String) = locked {
        if (version.isNotEmpty()) {
            db.update("UPDATE contacts SET client_version = ? WHERE ip = ?", listOf(version, ip))
        }
        Unit
    }

    fun updateContactDetails(ip: String, name: String, nickname: String, notes: String) = locked {
        db.update(
            "UPDATE contacts SET name = ?, nickname = ?, notes = ? WHERE ip = ?",
            listOf(name, nickname, notes, ip),
        )
        Unit
    }

    fun getContacts(): List<Contact> = locked {
        db.query("SELECT $CONTACT_COLS FROM contacts ORDER BY name COLLATE NOCASE", map = ::toContact)
    }

    fun getContact(ip: String): Contact? = locked {
        db.queryFirst("SELECT $CONTACT_COLS FROM contacts WHERE ip = ?", listOf(ip), ::toContact)
    }

    fun isBlocked(ip: String): Boolean = locked {
        db.queryFirst("SELECT is_blocked FROM contacts WHERE ip = ?", listOf(ip)) { it.boolean(0) } == true
    }

    /** Applies the given flags only; pinning stamps pinned_at so the order is stable. */
    fun setContactFlags(ip: String, patch: ContactFlagsPatch) = lockedTransaction {
        patch.isPinned?.let {
            db.update("UPDATE contacts SET pinned_at = ? WHERE ip = ?", listOf(if (it) now() else null, ip))
        }
        patch.isArchived?.let { db.update("UPDATE contacts SET is_archived = ? WHERE ip = ?", listOf(it, ip)) }
        patch.isBlocked?.let { db.update("UPDATE contacts SET is_blocked = ? WHERE ip = ?", listOf(it, ip)) }
        patch.muteUntil?.let { db.update("UPDATE contacts SET mute_until = ? WHERE ip = ?", listOf(it, ip)) }
        patch.isMarkedUnread?.let { db.update("UPDATE contacts SET is_marked_unread = ? WHERE ip = ?", listOf(it, ip)) }
        patch.disappearSeconds?.let {
            db.update("UPDATE contacts SET disappear_seconds = ? WHERE ip = ?", listOf(maxOf(it, 0), ip))
        }
        Unit
    }

    fun setContactPrivacy(ip: String, prefs: ContactPrivacyPrefs) = locked {
        db.update(
            "UPDATE contacts SET read_receipts = ?, typing_indicators = ?, block_screenshots = ?, reveal_gate = ? WHERE ip = ?",
            listOf(prefs.sendReadReceipts.asFlag(), prefs.sendTypingIndicators.asFlag(), prefs.blockScreenshots.asFlag(), prefs.revealGate?.wire, ip),
        )
        Unit
    }

    private fun Boolean?.asFlag(): Int? = this?.let { if (it) 1 else 0 }

    /** Stores the per-contact notification overrides; null restores "use the global settings". */
    fun setContactNotifications(ip: String, prefs: ContactNotificationPrefs?) = locked {
        val json = prefs?.let { CoreJson.encodeToString(ContactNotificationPrefs.serializer(), it) }
        db.update("UPDATE contacts SET notif_json = ? WHERE ip = ?", listOf(json, ip))
        Unit
    }
}

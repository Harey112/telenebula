package com.telenebula.core.db

import com.telenebula.core.CoreJson
import com.telenebula.core.model.ContactPrivacyPrefs
import com.telenebula.core.model.CallLog
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.Contact
import com.telenebula.core.model.ContactNotificationPrefs
import com.telenebula.core.model.MessageAction
import com.telenebula.core.model.MessageActionPayload
import com.telenebula.core.model.MessageAttachment
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

// a mapper indexes into its column list: a column added to one and not the other shifts the row
internal const val MSG_COLS = "id, peer_ip, direction, body, ts, status, kind, attachment_json, edited, deleted, " +
    "reactions_json, reply_to_id, seen_at, expire_secs, expires_at, read, cover_text"

internal const val ACTION_COLS = "id, message_id, peer_ip, type, payload_json, status, attempts, " +
    "created_at, updated_at"

internal const val CONTACT_COLS = "ip, name, nickname, notes, added_at, last_seen_at, pinned_at, is_archived, " +
    "is_blocked, mute_until, is_marked_unread, notif_json, client_version, disappear_seconds, read_receipts, typing_indicators, block_screenshots, reveal_gate"

internal const val CALL_COLS = "id, peer_ip, direction, is_video, outcome, started_at, connected_at, ended_at"

/** One row of [Store.peersWithOpenActions]: a peer with work, its depth and its oldest item. */
internal class PeerQueueRow(val peerIp: String, val queued: Int, val oldestAtMs: Long)

internal fun toContact(row: SqlRow) = Contact(
    ip = row.string(0),
    name = row.string(1),
    nickname = row.string(2),
    notes = row.string(3),
    addedAt = row.long(4),
    lastSeenAt = row.longOrNull(5),
    pinnedAt = row.longOrNull(6),
    isArchived = row.boolean(7),
    isBlocked = row.boolean(8),
    muteUntil = row.long(9),
    isMarkedUnread = row.boolean(10),
    notifications = row.stringOrNull(11)?.let {
        runCatching { CoreJson.decodeFromString(ContactNotificationPrefs.serializer(), it) }.getOrNull()
    },
    clientVersion = row.stringOrNull(12),
    disappearSeconds = row.int(13),
    privacy = ContactPrivacyPrefs(
        sendReadReceipts = row.longOrNull(14)?.let { it != 0L },
        sendTypingIndicators = row.longOrNull(15)?.let { it != 0L },
        blockScreenshots = row.longOrNull(16)?.let { it != 0L },
        revealGate = Wire.revealGate(row.stringOrNull(17)),
    ),
)

internal fun toMessage(row: SqlRow): ChatMessage {
    val direction = Wire.direction(row.stringOrNull(2))
    return ChatMessage(
        id = row.string(0),
        peerIp = row.string(1),
        direction = direction,
        body = row.string(3),
        ts = row.long(4),
        status = Wire.status(row.stringOrNull(5), direction),
        kind = Wire.kind(row.stringOrNull(6)),
        attachment = decodeAttachment(row.stringOrNull(7)),
        isEdited = row.boolean(8),
        isDeleted = row.boolean(9),
        reactions = decodeReactions(row.stringOrNull(10)),
        replyToId = row.stringOrNull(11),
        seenAt = row.longOrNull(12),
        expireSecs = row.longOrNull(13),
        expiresAt = row.longOrNull(14),
        isRead = row.boolean(15),
        cover = row.stringOrNull(16),
    )
}

internal fun toAction(row: SqlRow) = MessageAction(
    id = row.string(0),
    messageId = row.string(1),
    peerIp = row.string(2),
    type = Wire.actionType(row.stringOrNull(3)),
    payload = decodePayload(row.stringOrNull(4)),
    status = Wire.actionStatus(row.stringOrNull(5)),
    attempts = row.int(6),
    createdAt = row.long(7),
    updatedAt = row.long(8),
)

internal fun toCallLog(row: SqlRow) = CallLog(
    id = row.string(0),
    peerIp = row.string(1),
    direction = Wire.direction(row.stringOrNull(2)),
    isVideo = row.boolean(3),
    outcome = Wire.callOutcome(row.stringOrNull(4)),
    startedAt = row.long(5),
    connectedAt = row.longOrNull(6),
    endedAt = row.long(7),
)

private val REACTIONS = MapSerializer(String.serializer(), String.serializer())

internal fun encodeReactions(reactions: Map<String, String>): String =
    CoreJson.encodeToString(REACTIONS, reactions.toSortedMap())

internal fun decodeAttachment(json: String?): MessageAttachment? = json?.let {
    runCatching { CoreJson.decodeFromString(MessageAttachment.serializer(), it) }.getOrNull()
}

internal fun decodeReactions(json: String?): Map<String, String> {
    if (json.isNullOrEmpty() || json == "{}") return emptyMap()
    return runCatching { CoreJson.decodeFromString(REACTIONS, json) }.getOrNull() ?: emptyMap()
}

internal fun decodePayload(json: String?): MessageActionPayload = json?.let {
    runCatching { CoreJson.decodeFromString(MessageActionPayload.serializer(), it) }.getOrNull()
} ?: MessageActionPayload()

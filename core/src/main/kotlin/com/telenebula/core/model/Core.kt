package com.telenebula.core.model

import kotlinx.serialization.Serializable

/** One `getChatView` call returns everything the chat screen renders. */
@Serializable
data class ChatView(
    val contact: Contact? = null,
    /** ascending by ts, capped by the query limit */
    val messages: List<ChatMessage> = emptyList(),
    /** message id → its actions, oldest first */
    val actions: Map<String, List<MessageAction>> = emptyMap(),
    /** id of every message referenced by a replyToId → the source message */
    val replySources: Map<String, ChatMessage> = emptyMap(),
)

/** A place in one chat's history: the chat orders by (ts, id), so this names one row exactly. */
data class MessageCursor(val ts: Long, val id: String)

@Serializable
data class HomeBadges(val unreadTotal: Int = 0)

/** What starting the engine needs to know; the paths it works in are its own. */
data class CoreStartConfig(
    val overlayIp: String,
    val displayName: String,
    val msgPort: Int,
    val sendReadReceipts: Boolean,
    val appVersion: String,
)

package com.telenebula.app.nav

import androidx.navigation3.runtime.NavKey

enum class Tab { CHATS, CONTACTS, CALLS, ME }

/** One key per route of the RN app; `peerIp` is the peer's overlay IPv6, as `[user]` was. */
sealed interface TnKey : NavKey

/** The four tab roots; exactly one sits at the bottom of the stack while an identity exists. */
sealed interface TabKey : TnKey {
    val tab: Tab
}

data object Setup : TnKey
data object Call : TnKey

data object ChatsTab : TabKey { override val tab = Tab.CHATS }
data object ContactsTab : TabKey { override val tab = Tab.CONTACTS }
data object CallsTab : TabKey { override val tab = Tab.CALLS }
data object MeTab : TabKey { override val tab = Tab.ME }

/** A screen that shows one chat's content, so that chat's privacy applies to it. */
sealed interface ChatScopedKey : TnKey { val peerIp: String }
data class Chat(override val peerIp: String) : ChatScopedKey
data class ChatSettings(override val peerIp: String) : ChatScopedKey
data class ChatMedia(override val peerIp: String) : ChatScopedKey
data class ChatLinks(override val peerIp: String) : ChatScopedKey
data class ChatNotifications(override val peerIp: String) : ChatScopedKey
data object NewMessage : TnKey
data object ArchivedChats : TnKey

data class Contact(val peerIp: String) : TnKey
data class ContactCalls(val peerIp: String) : TnKey
data object NewContact : TnKey

data object Settings : TnKey
data object Dex : TnKey
data object Status : TnKey
data object Account : TnKey
data object RenewCertificate : TnKey
data object Appearance : TnKey
data object Privacy : TnKey
data object Blocked : TnKey
data object Network : TnKey
data object Lighthouse : TnKey
data object Diagnostics : TnKey
data object ChatPrefs : TnKey
data object CallPrefs : TnKey
data object NotificationPrefs : TnKey
data object Storage : TnKey
data object About : TnKey
data object Updates : TnKey

fun Tab.root(): TabKey = when (this) {
    Tab.CHATS -> ChatsTab
    Tab.CONTACTS -> ContactsTab
    Tab.CALLS -> CallsTab
    Tab.ME -> MeTab
}

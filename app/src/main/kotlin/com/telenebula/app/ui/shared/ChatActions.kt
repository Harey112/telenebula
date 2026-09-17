package com.telenebula.app.ui.shared

import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.notices.Prompt
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.platform.MUTE_FOREVER
import com.telenebula.app.ui.fragments.MenuOption
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.core.CoreClient
import com.telenebula.core.model.ChatSummary
import com.telenebula.core.model.ContactFlagsPatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Long-press menu for a chat row: pin, archive, mute, read state, block, delete. Shared by the chats and archived lists. */
class ChatActions(private val core: CoreClient, private val notices: NoticeCenter, private val scope: CoroutineScope) {
    private val optionsIp = MutableStateFlow<String?>(null)
    val selectedIp: StateFlow<String?> = optionsIp.asStateFlow()

    fun open(ip: String) {
        optionsIp.value = ip
    }

    fun close() {
        optionsIp.value = null
    }

    fun menu(chat: ChatSummary): List<MenuOption> {
        val muted = ContactLabels.isMuted(chat.muteUntil)
        val isUnread = chat.isMarkedUnread || chat.unread > 0
        fun apply(run: suspend () -> Unit): () -> Unit = {
            close()
            scope.launch { run() }
        }
        return listOf(
            MenuOption("pin", if (chat.pinnedAt != null) TnIcon.PIN_OFF else TnIcon.PIN, if (chat.pinnedAt != null) "Unpin" else "Pin", onClick = apply {
                core.setContactFlags(chat.ip, ContactFlagsPatch(isPinned = chat.pinnedAt == null))
            }),
            MenuOption("archive", if (chat.isArchived) TnIcon.UNARCHIVE else TnIcon.ARCHIVE, if (chat.isArchived) "Unarchive" else "Archive", onClick = apply {
                core.setContactFlags(chat.ip, ContactFlagsPatch(isArchived = !chat.isArchived))
            }),
            MenuOption("mute", if (muted) TnIcon.BELL else TnIcon.BELL_OFF, if (muted) "Unmute" else "Mute", onClick = apply {
                core.setContactFlags(chat.ip, ContactFlagsPatch(muteUntil = if (muted) 0L else MUTE_FOREVER))
            }),
            MenuOption("unread", if (isUnread) TnIcon.CHECK else TnIcon.MARK_UNREAD, if (isUnread) "Mark as read" else "Mark as unread", onClick = apply {
                if (isUnread) core.markChatRead(chat.ip) else core.setContactFlags(chat.ip, ContactFlagsPatch(isMarkedUnread = true))
            }),
            MenuOption("block", TnIcon.BLOCK, if (chat.isBlocked) "Unblock" else "Block", isDanger = !chat.isBlocked, onClick = apply {
                core.setContactFlags(chat.ip, ContactFlagsPatch(isBlocked = !chat.isBlocked))
            }),
            MenuOption("delete", TnIcon.TRASH, "Delete chat", isDanger = true) {
                close()
                notices.setPrompt(
                    Prompt(
                        message = "Delete chat\n\nDelete the chat and contact ${chat.name}?",
                        rightLabel = "Delete",
                        isDestructive = true,
                        onRight = {
                            scope.launch {
                                core.deleteContact(chat.ip)
                                notices.setSuccess("Chat with ${chat.name} was deleted.")
                            }
                        },
                    ),
                )
            },
        )
    }
}

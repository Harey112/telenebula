package com.telenebula.app.ui.screens.chats

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Chat
import com.telenebula.app.nav.Navigator
import com.telenebula.app.nav.NewMessage
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.app.runtime.TunnelPhase
import com.telenebula.app.ui.fragments.MenuOption
import com.telenebula.app.ui.shared.ChatActions
import com.telenebula.core.CoreClient
import com.telenebula.core.debounceAfterFirst
import com.telenebula.core.model.ChatSummary
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

data class ChatsUiState(
    /** false only before the very first read of the store: no "start a chat" hint yet */
    val isLoaded: Boolean = false,
    val chats: List<ChatSummary> = emptyList(),
    val isSearching: Boolean = false,
    val query: String = "",
    val isVpnRunning: Boolean = false,
    val headerTitle: String = "TeleNebula",
    val optionsChat: ChatSummary? = null,
    val menu: List<MenuOption> = emptyList(),
)

@Stable
interface ChatsActions {
    fun beginSearch()
    fun endSearch()
    fun openChat(ip: String)
    fun openOptions(ip: String)
    fun setQuery(value: String)
}

class ChatsViewModel(private val core: CoreClient, runtime: AppRuntime, notices: NoticeCenter, private val navigator: Navigator) : ViewModel(), ChatsActions {
    private class Search(val isSearching: Boolean = false, val query: String = "")

    private val search = MutableStateFlow(Search())
    private val actions = ChatActions(core, notices, viewModelScope)
    private val debouncedQuery = search.map { if (it.isSearching) it.query else "" }.distinctUntilChanged().debounceAfterFirst(150)

    // seeded from the core's last read, so a tab switch or a relaunch paints the list on its first frame
    val uiState: StateFlow<ChatsUiState> = combine(core.chatSummaries, debouncedQuery, search, runtime.tunnelPhase, actions.selectedIp, ::build)
        .uiState(viewModelScope, build(core.chatSummaries.value, "", search.value, runtime.tunnelPhase.value, actions.selectedIp.value))

    private fun build(all: List<ChatSummary>?, q: String, s: Search, phase: TunnelPhase, selected: String?): ChatsUiState {
        val chats = all.orEmpty()
        val needle = q.trim().lowercase()
        val visible = ArrayList<ChatSummary>(chats.size)
        for (chat in chats) {
            // a contact nobody has written to yet is not a chat
            if (chat.isArchived || chat.lastTs == null) continue
            if (needle.isEmpty() || chat.name.lowercase().contains(needle) || chat.ip.contains(needle)) visible.add(chat)
        }
        val options = selected?.let { ip -> chats.firstOrNull { it.ip == ip } }
        return ChatsUiState(
            isLoaded = all != null,
            chats = visible,
            isSearching = s.isSearching,
            query = s.query,
            isVpnRunning = phase == TunnelPhase.RUNNING,
            headerTitle = when (phase) {
                TunnelPhase.RUNNING -> "TeleNebula"
                TunnelPhase.STARTING -> "Connecting…"
                TunnelPhase.OFF -> "Offline"
            },
            optionsChat = options,
            menu = options?.let(actions::menu) ?: emptyList(),
        )
    }

    fun onShown() = core.refresh()
    override fun setQuery(value: String) = search.update { Search(it.isSearching, value) }
    override fun beginSearch() = search.update { Search(true, it.query) }
    override fun endSearch() = search.update { Search(false, "") }

    override fun openChat(ip: String) {
        core.warmChat(ip)
        navigator.push(Chat(ip))
    }
    fun openNewMessage() = navigator.push(NewMessage)
    override fun openOptions(ip: String) = actions.open(ip)
    fun closeOptions() = actions.close()
}

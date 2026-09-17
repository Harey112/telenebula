package com.telenebula.app.ui.screens.archived

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Chat
import com.telenebula.app.nav.Navigator
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.ui.fragments.MenuOption
import com.telenebula.app.ui.shared.ChatActions
import com.telenebula.core.CoreClient
import com.telenebula.core.model.ChatSummary
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class ArchivedUiState(val chats: List<ChatSummary> = emptyList(), val optionsChat: ChatSummary? = null, val menu: List<MenuOption> = emptyList())

class ArchivedChatsViewModel(private val core: CoreClient, notices: NoticeCenter, private val navigator: Navigator) : ViewModel() {
    private val actions = ChatActions(core, notices, viewModelScope)

    val uiState: StateFlow<ArchivedUiState> = combine(core.chatSummaries, actions.selectedIp, ::build)
        .uiState(viewModelScope, build(core.chatSummaries.value, actions.selectedIp.value))

    private fun build(all: List<ChatSummary>?, selected: String?): ArchivedUiState {
        val archived = all.orEmpty().filter { it.isArchived && it.lastTs != null }
        val options = selected?.let { ip -> archived.firstOrNull { it.ip == ip } }
        return ArchivedUiState(archived, options, options?.let(actions::menu) ?: emptyList())
    }

    fun onShown() = core.refresh()

    fun openChat(ip: String) {
        core.warmChat(ip)
        navigator.push(Chat(ip))
    }
    fun openOptions(ip: String) = actions.open(ip)
    fun closeOptions() = actions.close()
    fun goBack() = navigator.pop()
}

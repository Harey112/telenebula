package com.telenebula.app.ui.screens.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Chat
import com.telenebula.app.nav.Navigator
import com.telenebula.app.ui.shared.uiState
import com.telenebula.core.CoreClient
import com.telenebula.core.model.ChatSummary
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

data class MessageRequestsUiState(val chats: List<ChatSummary> = emptyList())

class MessageRequestsViewModel(private val core: CoreClient, private val navigator: Navigator) : ViewModel() {
    val uiState: StateFlow<MessageRequestsUiState> = core.chatSummaries.map(::build)
        .uiState(viewModelScope, build(core.chatSummaries.value))

    private fun build(all: List<ChatSummary>?): MessageRequestsUiState =
        MessageRequestsUiState(all.orEmpty().filter { it.isRequest && !it.isBlocked && it.lastTs != null })

    fun onShown() = core.refresh()

    fun openChat(ip: String) {
        core.warmChat(ip)
        navigator.push(Chat(ip))
    }

    fun goBack() = navigator.pop()
}

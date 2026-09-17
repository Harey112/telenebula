package com.telenebula.app.ui.screens.newmessage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Chat
import com.telenebula.app.nav.Navigator
import com.telenebula.app.nav.NewContact
import com.telenebula.app.platform.ContactLabels
import com.telenebula.core.CoreClient
import com.telenebula.core.debounceAfterFirst
import com.telenebula.core.model.Contact
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

class LabeledContact(val ip: String, val label: String, val name: String)

data class NewMessageUiState(val query: String = "", val contacts: List<LabeledContact> = emptyList())

class NewMessageViewModel(private val core: CoreClient, private val navigator: Navigator) : ViewModel() {
    private val query = MutableStateFlow("")

    val uiState: StateFlow<NewMessageUiState> = combine(core.contacts, query.debounceAfterFirst(150), query, ::build)
        .uiState(viewModelScope, build(core.contacts.value, "", ""))

    private fun build(contacts: List<Contact>?, q: String, live: String): NewMessageUiState {
        val needle = q.trim().lowercase()
        val labeled = contacts.orEmpty().sortedByDescending { it.lastSeenAt ?: 0L }.map { LabeledContact(it.ip, ContactLabels.chatLabel(it), it.name) }
        val shown = if (needle.isEmpty()) labeled else labeled.filter { it.label.lowercase().contains(needle) || it.name.lowercase().contains(needle) || it.ip.contains(needle) }
        return NewMessageUiState(live, shown)
    }

    fun setQuery(value: String) {
        query.value = value
    }

    /** Replaces this picker with the chat, as `router.replace` did. */
    fun openChat(ip: String) {
        core.warmChat(ip)
        navigator.pop()
        navigator.push(Chat(ip))
    }

    fun openNewContact() = navigator.push(NewContact)
    fun goBack() = navigator.pop()
}

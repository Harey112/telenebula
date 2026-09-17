package com.telenebula.app.ui.screens.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Contact as ContactKey
import com.telenebula.app.nav.Navigator
import com.telenebula.app.nav.NewContact
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.notices.Prompt
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.ui.fragments.MenuOption
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.core.CoreClient
import com.telenebula.core.debounceAfterFirst
import com.telenebula.core.model.Contact
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [nickname] is shown under the username only when both are known and differ. */
class ContactListItem(val ip: String, val label: String, val nickname: String? = null)

data class ContactsUiState(
    /** false only before the very first read of the store: no "no contacts yet" hint yet */
    val isLoaded: Boolean = false,
    val query: String = "",
    val contacts: List<ContactListItem> = emptyList(),
    val optionsContact: Contact? = null,
    val isRenaming: Boolean = false,
    val renameDraft: String = "",
)

class ContactsViewModel(private val core: CoreClient, private val notices: NoticeCenter, private val navigator: Navigator) : ViewModel() {
    private class Local(val query: String = "", val optionsIp: String? = null, val isRenaming: Boolean = false, val renameDraft: String = "")

    private val local = MutableStateFlow(Local())
    private val debouncedQuery = local.map { it.query }.distinctUntilChanged().debounceAfterFirst(150)

    /** sorted once per contact list, not once per keystroke */
    private val sorted = core.contacts.map(::sortedLabels)

    // seeded from the core's last read, so a tab switch or a relaunch paints the list on its first frame
    val uiState: StateFlow<ContactsUiState> = combine(core.contacts, sorted, debouncedQuery, local, ::build)
        .uiState(viewModelScope, build(core.contacts.value, sortedLabels(core.contacts.value), local.value.query, local.value))

    private fun sortedLabels(all: List<Contact>?): List<Pair<Contact, String>> = all.orEmpty()
        .map { it to ContactLabels.contactLabel(it) }
        .sortedWith(compareByDescending<Pair<Contact, String>> { it.first.lastSeenAt ?: 0L }.thenBy { it.second })

    private fun build(all: List<Contact>?, labeled: List<Pair<Contact, String>>, query: String, l: Local): ContactsUiState {
        val needle = query.trim().lowercase()
        val shown = ArrayList<ContactListItem>(labeled.size)
        for ((c, label) in labeled) {
            if (needle.isEmpty() || label.lowercase().contains(needle) || c.nickname.lowercase().contains(needle) || c.ip.contains(needle)) {
                shown.add(ContactListItem(c.ip, label, c.nickname.takeIf { it.isNotEmpty() && it != label }))
            }
        }
        return ContactsUiState(all != null, l.query, shown, l.optionsIp?.let { ip -> all.orEmpty().firstOrNull { it.ip == ip } }, l.isRenaming, l.renameDraft)
    }

    fun menu(contact: Contact): List<MenuOption> = listOf(
        MenuOption("edit", TnIcon.PENCIL, "Edit nickname") { local.update { Local(it.query, it.optionsIp, isRenaming = true, renameDraft = contact.nickname) } },
        MenuOption("delete", TnIcon.TRASH, "Delete", isDanger = true) { deleteFromOptions(contact) },
    )

    fun setQuery(value: String) = local.update { Local(value, it.optionsIp, it.isRenaming, it.renameDraft) }
    fun openContact(ip: String) = navigator.push(ContactKey(ip))
    fun openNewContact() = navigator.push(NewContact)
    fun openOptions(ip: String) = local.update { Local(it.query, ip) }
    fun closeOptions() = local.update { Local(it.query) }
    fun setRenameDraft(value: String) = local.update { Local(it.query, it.optionsIp, it.isRenaming, value) }
    fun cancelRename() = local.update { Local(it.query) }

    /** The username is the peer's; only the nickname is ours to edit. */
    fun saveRename() {
        val s = local.value
        val contact = uiState.value.optionsContact
        local.value = Local(s.query)
        if (contact == null) return
        viewModelScope.launch { core.updateContactDetails(contact.ip, contact.name, s.renameDraft.trim(), contact.notes) }
    }

    private fun deleteFromOptions(contact: Contact) {
        local.update { Local(it.query) }
        notices.setPrompt(
            Prompt(
                message = "Delete contact\n\nRemove ${contact.name} and the chat history?",
                rightLabel = "Delete",
                isDestructive = true,
                onRight = {
                    viewModelScope.launch {
                        core.deleteContact(contact.ip)
                        notices.setSuccess("${contact.name} was deleted.")
                    }
                },
            ),
        )
    }
}

package com.telenebula.app.ui.screens.blocked

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Navigator
import com.telenebula.app.platform.ContactLabels
import com.telenebula.core.CoreClient
import com.telenebula.core.model.Contact
import com.telenebula.core.model.ContactFlagsPatch
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class BlockedRow(val ip: String, val label: String)

class BlockedViewModel(private val core: CoreClient, private val navigator: Navigator) : ViewModel() {
    val contacts: StateFlow<List<BlockedRow>> = core.contacts.map(::rows)
        .uiState(viewModelScope, rows(core.contacts.value))

    private fun rows(all: List<Contact>?): List<BlockedRow> = all.orEmpty().filter { it.isBlocked }.map { BlockedRow(it.ip, ContactLabels.contactLabel(it)) }

    fun unblock(ip: String) {
        viewModelScope.launch { core.setContactFlags(ip, ContactFlagsPatch(isBlocked = false)) }
    }

    fun goBack() = navigator.pop()
}

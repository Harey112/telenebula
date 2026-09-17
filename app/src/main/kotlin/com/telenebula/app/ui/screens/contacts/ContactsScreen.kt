package com.telenebula.app.ui.screens.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.ContactRow
import com.telenebula.app.ui.fragments.EditNameModal
import com.telenebula.app.ui.fragments.Fab
import com.telenebula.app.ui.fragments.OptionsMenu
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.SearchBar
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun ContactsScreen(viewModel: ContactsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TnTheme.colors
    Screen(title = "Contacts", isScrolling = false) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                SearchBar(state.query, viewModel::setQuery, placeholder = "Search Contacts")
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(TnSpace.lg)
                        .clip(RoundedCornerShape(TnRadius.lg))
                        .background(colors.surface),
                ) {
                    Text("Sorted by last seen time", style = TnType.small, color = colors.textMuted, modifier = Modifier.padding(start = TnSpace.lg, end = TnSpace.lg, top = TnSpace.md))
                    if (state.isLoaded && state.contacts.isEmpty()) {
                        Text("No contacts yet — add someone by their nebula IPv6 number.", style = TnType.body, color = colors.textMuted, modifier = Modifier.padding(TnSpace.xl))
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.contacts, key = { it.ip }) { c -> ContactRow(c.label, c.ip, onClick = viewModel::openContact, onLongClick = viewModel::openOptions, detail = c.nickname) }
                        }
                    }
                }
            }
            Fab(TnIcon.PERSON_ADD, "Add contact", viewModel::openNewContact, modifier = Modifier.align(Alignment.BottomEnd).padding(end = TnSpace.lg, bottom = TnSpace.xl))
        }
    }
    val options = state.optionsContact
    OptionsMenu(isVisible = options != null && !state.isRenaming, options = options?.let(viewModel::menu) ?: emptyList(), onClose = viewModel::closeOptions, title = options?.name)
    EditNameModal(
        isVisible = state.isRenaming,
        value = state.renameDraft,
        onChange = viewModel::setRenameDraft,
        onCancel = viewModel::cancelRename,
        onSave = viewModel::saveRename,
        title = "Edit nickname",
        placeholder = "Nickname",
    )
}

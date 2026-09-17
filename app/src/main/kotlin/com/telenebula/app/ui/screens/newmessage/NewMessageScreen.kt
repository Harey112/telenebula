package com.telenebula.app.ui.screens.newmessage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.ContactRow
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.SearchBar
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnRow
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType
import com.telenebula.core.model.Contact

@Composable
fun NewMessageScreen(viewModel: NewMessageViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TnTheme.colors
    Screen(title = "New Message", onBack = viewModel::goBack, isScrolling = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TnSpace.lg)
                .padding(top = TnSpace.lg)
                .clip(RoundedCornerShape(TnRadius.lg))
                .background(colors.surface)
                .clickable(role = Role.Button, onClick = viewModel::openNewContact)
                .semantics { contentDescription = "New contact" }
                .defaultMinSize(minHeight = TnRow.height)
                .padding(horizontal = TnSpace.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
        ) {
            Box(modifier = Modifier.size(TnRow.iconTile).clip(RoundedCornerShape(TnRadius.sm)).background(colors.accent), contentAlignment = Alignment.Center) {
                Icon(TnIcon.PERSON_ADD, tint = colors.onAccent, size = 18.dp)
            }
            Text("New Contact", style = TnType.body.copy(fontWeight = FontWeight.Medium), color = colors.text)
        }
        Box(modifier = Modifier.padding(top = TnSpace.lg)) { SearchBar(state.query, viewModel::setQuery, placeholder = "Search Contacts") }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(TnSpace.lg)
                .clip(RoundedCornerShape(TnRadius.lg))
                .background(colors.surface),
        ) {
            if (state.contacts.isEmpty()) {
                Text("No contacts yet — create one to start chatting.", style = TnType.body, color = colors.textMuted, modifier = Modifier.padding(TnSpace.xl))
            } else {
                Text("Sorted by last seen time", style = TnType.small, color = colors.textMuted, modifier = Modifier.padding(start = TnSpace.lg, end = TnSpace.lg, top = TnSpace.md))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.contacts, key = { it.ip }) { c -> ContactRow(c.label, c.ip, onClick = viewModel::openChat) }
                }
            }
        }
    }
}

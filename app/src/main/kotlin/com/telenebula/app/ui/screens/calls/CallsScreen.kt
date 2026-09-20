package com.telenebula.app.ui.screens.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.platform.CallLogRowData
import com.telenebula.app.ui.fragments.Avatar
import com.telenebula.app.ui.fragments.ErrorBanner
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.SearchHeader
import com.telenebula.app.ui.fragments.SelectOption
import com.telenebula.app.ui.fragments.SelectMenuRow
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRow
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun CallsScreen(viewModel: CallsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onShown() }
    Screen(isScrolling = false) {
        if (state.isSelecting) {
            SelectionBar(count = state.selectedIds.size, onClear = viewModel::clearSelection, onDelete = viewModel::deleteSelected)
        } else {
            SearchHeader(
                title = "Calls",
                isSearching = state.isSearching,
                query = state.query,
                onQuery = viewModel::setQuery,
                onBeginSearch = viewModel::beginSearch,
                onEndSearch = viewModel::endSearch,
                placeholder = "Search calls",
                searchLabel = "Search calls",
            ) { FilterToggle(isOpen = state.isFilterPanelOpen, isActive = state.isFiltered, onToggle = viewModel::toggleFilterPanel) }
        }
        if (state.isFilterPanelOpen && !state.isSelecting) FilterPanel(state, viewModel.filterOptions, viewModel.sortOptions, viewModel)
        state.error?.let { ErrorBanner(it, modifier = Modifier.padding(horizontal = TnSpace.lg), onDismiss = viewModel::clearError) }
        if (state.rows.isEmpty()) {
            if (state.isLoaded) EmptyCalls(hasCalls = state.hasCalls)
        } else {
            CallList(state, viewModel)
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onClear: () -> Unit, onDelete: () -> Unit) {
    val colors = TnTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(TnRow.height).padding(horizontal = TnSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
    ) {
        Icon(TnIcon.CLOSE, tint = colors.text, contentDescription = "Clear selection", modifier = Modifier.clickable(role = Role.Button, onClick = onClear))
        Text("$count selected", style = TnType.heading, color = colors.text)
        Spacer(modifier = Modifier.weight(1f))
        Icon(TnIcon.TRASH, tint = colors.danger, size = 23.dp, contentDescription = "Delete selected calls", modifier = Modifier.clickable(role = Role.Button, onClick = onDelete))
    }
}

@Composable
private fun FilterToggle(isOpen: Boolean, isActive: Boolean, onToggle: () -> Unit) {
    val colors = TnTheme.colors
    Icon(
        TnIcon.LIST,
        tint = if (isOpen || isActive) colors.accent else colors.text,
        size = 23.dp,
        contentDescription = "Filter and sort",
        modifier = Modifier.clickable(role = Role.Button, onClick = onToggle).semantics { selected = isOpen },
    )
}

@Composable
private fun FilterPanel(state: CallsUiState, filterOptions: List<SelectOption>, sortOptions: List<SelectOption>, actions: CallsActions) {
    SelectMenuRow("Show", filterOptions, state.filter.key, actions::setFilter)
    SelectMenuRow("Sort by", sortOptions, state.sort.key, actions::setSort)
}

@Composable
private fun EmptyCalls(hasCalls: Boolean) {
    Box(modifier = Modifier.fillMaxSize().padding(TnSpace.xl), contentAlignment = Alignment.Center) {
        Text(
            if (hasCalls) "No calls match." else "No calls yet. Start one from a chat or a contact.",
            style = TnType.body,
            color = TnTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CallList(state: CallsUiState, actions: CallsActions) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.rows, key = { it.id }, contentType = { "call" }) { row ->
            CallRow(row, state.isTunnelOn, isSelecting = state.isSelecting, isSelected = row.id in state.selectedIds, actions)
        }
    }
}

@Composable
private fun CallRow(row: CallLogRowData, isTunnelOn: Boolean, isSelecting: Boolean, isSelected: Boolean, actions: CallsActions) {
    val colors = TnTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) colors.accentSoft else colors.background)
            .combinedClickable(role = Role.Button, onClick = { actions.tapRow(row.id, row.peerIp) }, onLongClick = { actions.longPressRow(row.id) })
            .semantics {
                contentDescription = "${row.title}, ${row.subtitle}"
                if (isSelecting) selected = isSelected
            }
            .padding(horizontal = TnSpace.lg, vertical = TnSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
    ) {
        if (isSelecting) SelectionMark(isSelected)
        Avatar(row.title, size = TnRow.avatar)
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, style = TnType.body.copy(fontWeight = FontWeight.Medium), color = if (row.isMissed) colors.danger else colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TnSpace.xs), modifier = Modifier.padding(top = 2.dp)) {
                Icon(row.icon, tint = if (row.isMissed) colors.danger else colors.textMuted, size = 14.dp)
                Text(row.subtitle, style = TnType.small, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!isSelecting) {
            Icon(
                if (row.isVideo) TnIcon.VIDEO else TnIcon.CALL,
                tint = if (isTunnelOn) colors.accent else colors.hairline,
                size = 22.dp,
                contentDescription = if (row.isVideo) "Video call back" else "Call back",
                modifier = Modifier.size(44.dp).clickable(enabled = isTunnelOn, role = Role.Button) { actions.callBack(row.peerIp, row.isVideo) }.padding(11.dp),
            )
        }
    }
}

@Composable
private fun SelectionMark(isSelected: Boolean) {
    val colors = TnTheme.colors
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (isSelected) colors.accent else colors.background)
            .border(1.5.dp, if (isSelected) colors.accent else colors.hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) Icon(TnIcon.CHECK, tint = colors.onAccent, size = 16.dp)
    }
}

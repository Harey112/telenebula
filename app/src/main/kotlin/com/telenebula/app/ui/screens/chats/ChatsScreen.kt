package com.telenebula.app.ui.screens.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.ChatListRow
import com.telenebula.app.ui.fragments.Fab
import com.telenebula.app.ui.fragments.OptionsMenu
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.SearchHeader
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun ChatsScreen(viewModel: ChatsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onShown() }
    Screen(isScrolling = false) {
        SearchHeader(
            title = state.headerTitle,
            isSearching = state.isSearching,
            query = state.query,
            onQuery = viewModel::setQuery,
            onBeginSearch = viewModel::beginSearch,
            onEndSearch = viewModel::endSearch,
            searchLabel = "Search chats",
        )
        Box(modifier = Modifier.fillMaxSize()) {
            ChatList(state, viewModel)
            Fab(TnIcon.PENCIL, "New message", viewModel::openNewMessage, modifier = Modifier.align(Alignment.BottomEnd).padding(end = TnSpace.lg, bottom = TnSpace.xl))
        }
    }
    OptionsMenu(isVisible = state.optionsChat != null, options = state.menu, onClose = viewModel::closeOptions, title = state.optionsChat?.name)
}

@Composable
private fun ChatList(state: ChatsUiState, actions: ChatsActions) {
    val colors = TnTheme.colors
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.chats, key = { it.ip }, contentType = { "chat" }) { chat ->
            ChatListRow(chat, onClick = actions::openChat, onLongClick = actions::openOptions)
        }
        if (state.isLoaded && state.chats.isEmpty()) {
            item(key = "empty") {
                Box(modifier = Modifier.fillMaxWidth().padding(TnSpace.xl), contentAlignment = Alignment.Center) {
                    Text("Tap on the button to start a new chat", style = TnType.body, color = colors.textMuted)
                }
            }
        }
    }
}

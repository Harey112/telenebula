package com.telenebula.app.ui.screens.requests

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.ChatListRow
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun MessageRequestsScreen(viewModel: MessageRequestsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onShown() }
    Screen(title = "Message requests", onBack = viewModel::goBack, isScrolling = false) {
        if (state.chats.isEmpty()) {
            Text("No message requests.", style = TnType.body, color = TnTheme.colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(TnSpace.xl))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.chats, key = { it.ip }, contentType = { "chat" }) { chat -> ChatListRow(chat, onClick = viewModel::openChat, onLongClick = viewModel::openChat) }
            }
        }
    }
}

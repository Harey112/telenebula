package com.telenebula.app.ui.screens.chatmedia

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.MediaTile
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun ChatMediaScreen(viewModel: ChatMediaViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Screen(title = "Media · ${state.peerName}", onBack = viewModel::goBack, isScrolling = false) {
        if (state.items.isEmpty()) {
            Text("No photos, videos or files yet.", style = TnType.body, color = TnTheme.colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(TnSpace.xl))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(TnSpace.lg),
                horizontalArrangement = Arrangement.spacedBy(TnSpace.xs),
                verticalArrangement = Arrangement.spacedBy(TnSpace.xs),
            ) {
                items(state.items, key = { it.id }) { msg -> MediaTile(msg, onClick = { viewModel.openItem(msg) }, showName = true) }
            }
        }
    }
}

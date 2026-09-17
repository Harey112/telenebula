package com.telenebula.app.ui.screens.contactcalls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.CallLogRow
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun ContactCallsScreen(viewModel: ContactCallsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TnTheme.colors
    Screen(title = "Calls · ${state.peerName}", onBack = viewModel::goBack, isScrolling = false) {
        Box(modifier = Modifier.fillMaxSize().padding(TnSpace.lg).clip(RoundedCornerShape(TnRadius.lg)).background(colors.surface)) {
            if (state.items.isEmpty()) {
                Text("No calls yet.", style = TnType.body, color = colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(TnSpace.xl))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) { items(state.items, key = { it.id }) { CallLogRow(it) } }
            }
        }
    }
}

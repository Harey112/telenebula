package com.telenebula.app.ui.screens.blocked

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.Avatar
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.theme.TnRow
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun BlockedScreen(viewModel: BlockedViewModel) {
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val colors = TnTheme.colors
    Screen(title = "Blocked contacts", onBack = viewModel::goBack, isScrolling = false) {
        if (contacts.isEmpty()) {
            Text("Nobody is blocked.", style = TnType.body, color = colors.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(TnSpace.xl))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(contacts, key = { it.ip }) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = TnRow.height).padding(horizontal = TnSpace.lg, vertical = TnSpace.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
                    ) {
                        Avatar(item.label, size = 44.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.label, style = TnType.body.copy(fontWeight = FontWeight.Medium), color = colors.text)
                            Text(item.ip, style = TnType.caption.copy(fontFamily = FontFamily.Monospace), color = colors.textMuted)
                        }
                        Text(
                            "Unblock",
                            style = TnType.small.copy(fontWeight = FontWeight.Medium),
                            color = colors.accent,
                            modifier = Modifier
                                .clickable(role = Role.Button) { viewModel.unblock(item.ip) }
                                .semantics { contentDescription = "Unblock ${item.label}" }
                                .padding(TnSpace.sm),
                        )
                    }
                }
            }
        }
    }
}

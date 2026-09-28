package com.telenebula.app.ui.screens.storage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.platform.Format
import com.telenebula.app.ui.fragments.InfoField
import com.telenebula.app.ui.fragments.RowTone
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.fragments.SwitchRow
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnTheme
import kotlinx.coroutines.launch

@Composable
fun StorageScreen(viewModel: StorageViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val s = state.stats
    Screen(
        title = "Storage",
        onBack = viewModel::goBack,
        trailing = {
            Icon(TnIcon.RETRY, tint = TnTheme.colors.text, size = 20.dp, contentDescription = "Refresh", modifier = Modifier.clickable(role = Role.Button, onClick = viewModel::refresh).padding(4.dp))
        },
    ) {
        Section(title = "Usage") {
            row { InfoField("Message database · ${s.messages} messages, ${s.contacts} contacts", Format.bytes(s.dbBytes)) }
            row { InfoField("Media and files · ${s.attachmentsCount} files", Format.bytes(s.attachmentsBytes)) }
            row { InfoField("Unreferenced media · ${s.orphanCount} files", Format.bytes(s.orphanBytes)) }
            if (s.partialCount > 0) row { InfoField("Downloads still in progress · ${s.partialCount} files", Format.bytes(s.partialBytes)) }
        }
        Section(title = "Cleanup", help = "Removing unreferenced media deletes files no message uses and gives up downloads stuck for a week, deleting their partial files; with automatic cleanup it runs every time the app starts. Clearing all chat history deletes every message on this phone; contacts stay.") {
            row { SettingRow(TnIcon.CLEAR, "Remove unreferenced media", onClick = viewModel::clearOrphans) }
            row { SwitchRow(TnIcon.RETRY, "Clean automatically on start", state.autoCleanOrphans, viewModel::toggleAutoClean) }
            row { SettingRow(TnIcon.TRASH, "Clear all chat history", onClick = viewModel::clearAllHistory, tone = RowTone.DANGER) }
        }
    }
}

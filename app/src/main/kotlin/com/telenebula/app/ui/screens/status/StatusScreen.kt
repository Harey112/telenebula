package com.telenebula.app.ui.screens.status

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.InfoField
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SelectMenuRow
import com.telenebula.app.ui.fragments.SwitchRow
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun StatusScreen(viewModel: StatusViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Screen(title = "Status", onBack = viewModel::goBack) {
        Section(
            title = "Sharing",
            footnote = "Online: you are using the app and share it. Reachable: your device answers but you are away, or sharing is off. Offline: your device does not answer.",
        ) {
            row {
                SwitchRow(
                    TnIcon.EYE,
                    "Active status",
                    state.isActive,
                    viewModel::toggleActive,
                    subtitle = if (state.isActive) "Contacts see “online” while you use the app" else "Contacts only see “reachable”",
                )
            }
            row { SelectMenuRow("Pause for", viewModel.pauseOptions, state.pauseKey, viewModel::setPause) }
            state.pauseNote?.let { note ->
                row { Text(note, style = TnType.small, color = TnTheme.colors.textMuted, modifier = Modifier.padding(horizontal = TnSpace.lg, vertical = TnSpace.sm)) }
            }
        }
        Section(title = "Right now") {
            row { InfoField("Contacts see you as", state.seenAs) }
        }
    }
}

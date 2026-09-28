package com.telenebula.app.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.nav.About
import com.telenebula.app.nav.Account
import com.telenebula.app.nav.Appearance
import com.telenebula.app.nav.CallPrefs
import com.telenebula.app.nav.ChatPrefs
import com.telenebula.app.nav.Diagnostics
import com.telenebula.app.nav.Network
import com.telenebula.app.nav.NotificationPrefs
import com.telenebula.app.nav.Privacy
import com.telenebula.app.nav.Storage
import com.telenebula.app.nav.Updates
import com.telenebula.app.ui.fragments.DotBadge
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.icons.TnIcon

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Screen(title = "Settings", onBack = viewModel::goBack) {
        IdentitySection(state, viewModel)
        ConnectionSection(viewModel)
        PreferencesSection(viewModel)
        AppSection(state, viewModel)
    }
}

@Composable
private fun IdentitySection(state: SettingsUiState, actions: SettingsActions) {
    Section(title = "Identity") {
        row { SettingRow(TnIcon.ACCOUNT, "Account", subtitle = state.accountSubtitle, onClick = { actions.open(Account) }) }
        row { SettingRow(TnIcon.LOCK, "Security & privacy", onClick = { actions.open(Privacy) }) }
    }
}

@Composable
private fun ConnectionSection(actions: SettingsActions) {
    Section(title = "Connection") {
        row { SettingRow(TnIcon.SHIELD, "Network", onClick = { actions.open(Network) }) }
        row { SettingRow(TnIcon.RETRY, "Diagnostics", onClick = { actions.open(Diagnostics) }) }
    }
}

@Composable
private fun PreferencesSection(actions: SettingsActions) {
    Section(title = "Preferences") {
        row { SettingRow(TnIcon.EYE, "Appearance", onClick = { actions.open(Appearance) }) }
        row { SettingRow(TnIcon.CHATS, "Chats", onClick = { actions.open(ChatPrefs) }) }
        row { SettingRow(TnIcon.CALL, "Calls", onClick = { actions.open(CallPrefs) }) }
        row { SettingRow(TnIcon.BELL, "Notifications and sounds", onClick = { actions.open(NotificationPrefs) }) }
        row { SettingRow(TnIcon.FOLDER, "Storage", onClick = { actions.open(Storage) }) }
    }
}

@Composable
private fun AppSection(state: SettingsUiState, actions: SettingsActions) {
    Section(title = "App") {
        row { SettingRow(TnIcon.INFO, "About", onClick = { actions.open(About) }) }
        row {
            SettingRow(
                TnIcon.RETRY,
                "Updates",
                subtitle = if (state.hasUpdate) "A newer release is ready to install" else "Check for a newer release",
                onClick = { actions.open(Updates) },
                trailing = if (state.hasUpdate) ({ DotBadge("Update available") }) else null,
            )
        }
    }
}

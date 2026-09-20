package com.telenebula.app.ui.screens.chatnotifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SelectMenuRow
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.fragments.SwitchRow
import com.telenebula.app.ui.icons.TnIcon

@Composable
fun ChatNotificationsScreen(viewModel: ChatNotificationsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val p = state.prefs
    Screen(title = "Notifications and sounds", onBack = viewModel::goBack) {
        Section(title = state.title) {
            row { SwitchRow(if (state.isEnabled) TnIcon.BELL else TnIcon.BELL_OFF, "Notifications", state.isEnabled, viewModel::toggleEnabled, subtitle = state.muteStatus) }
            if (state.isEnabled) row { SelectMenuRow("Mute for", viewModel.muteOptions, "", viewModel::muteFor) }
        }
        Section(title = "Messages") {
            row { SwitchRow(TnIcon.SETTINGS, "Customize for this contact", state.isCustomized, viewModel::toggleCustomized, subtitle = if (state.isCustomized) "Using these settings" else "Using the global settings") }
            if (state.isCustomized) {
                row { SwitchRow(TnIcon.CHATS, "Message notifications", p.messages, { viewModel.commit(p.copy(messages = !p.messages)) }) }
                row { SwitchRow(TnIcon.LIST, "Message preview", p.preview, { viewModel.commit(p.copy(preview = !p.preview)) }, subtitle = "Show the text, not just “New message”") }
                row { SwitchRow(TnIcon.SPEAKER, "Sound", p.sound, { viewModel.commit(p.copy(sound = !p.sound)) }) }
                row { SwitchRow(TnIcon.VIBRATE, "Vibrate", p.vibrate, { viewModel.commit(p.copy(vibrate = !p.vibrate)) }) }
                row { SwitchRow(TnIcon.BELL, "Pop-up notification", p.popup, { viewModel.commit(p.copy(popup = !p.popup)) }, subtitle = "Show at the top of the screen") }
                row { SwitchRow(TnIcon.EMOJI, "Reactions", p.reactions, { viewModel.commit(p.copy(reactions = !p.reactions)) }, subtitle = "When they react to your messages") }
            }
        }
        Section(title = "Calls") {
            row { SwitchRow(TnIcon.CALL, "Allow calls", p.calls, { viewModel.commit(p.copy(calls = !p.calls)) }, subtitle = "Off declines their calls automatically") }
        }
        Section(footnote = "Your phone’s sound profile and Do Not Disturb always apply on top of these settings.") {
            row { SettingRow(TnIcon.RETRY, "Reset to global settings", onClick = viewModel::resetToGlobal) }
        }
    }
}

package com.telenebula.app.ui.screens.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SelectOption
import com.telenebula.app.ui.fragments.SelectMenu
import com.telenebula.app.ui.fragments.SelectMenuRow
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.fragments.SwitchRow
import com.telenebula.app.ui.icons.TnIcon

@Composable
fun NotificationsScreen(viewModel: NotificationsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val n = state.prefs
    val hourOptions = remember { List(24) { h -> SelectOption(h.toString(), "%02d:00".format(h)) } }
    Screen(title = "Notifications and sounds", onBack = viewModel::goBack) {
        Section(title = "Messages") {
            row { SwitchRow(TnIcon.CHATS, "Show notifications", n.messages.enabled, { viewModel.update { it.copy(messages = it.messages.copy(enabled = !it.messages.enabled)) } }) }
            row { SwitchRow(TnIcon.PERSON, "Show sender name", n.messages.showSender, { viewModel.update { it.copy(messages = it.messages.copy(showSender = !it.messages.showSender)) } }, subtitle = "Off shows “TeleNebula” instead") }
            row { SwitchRow(TnIcon.LIST, "Message preview", n.messages.preview, { viewModel.update { it.copy(messages = it.messages.copy(preview = !it.messages.preview)) } }, subtitle = "Off shows “New message” instead of the text") }
            row { SwitchRow(TnIcon.SPEAKER, "Sound", n.messages.sound, { viewModel.update { it.copy(messages = it.messages.copy(sound = !it.messages.sound)) } }) }
            row { SwitchRow(TnIcon.VIBRATE, "Vibrate", n.messages.vibrate, { viewModel.update { it.copy(messages = it.messages.copy(vibrate = !it.messages.vibrate)) } }) }
            row { SwitchRow(TnIcon.BELL, "Pop-up notifications", n.messages.popup, { viewModel.update { it.copy(messages = it.messages.copy(popup = !it.messages.popup)) } }, subtitle = "Appear at the top of the screen") }
            row { SwitchRow(TnIcon.EMOJI, "Reaction notifications", n.messages.reactions, { viewModel.update { it.copy(messages = it.messages.copy(reactions = !it.messages.reactions)) } }, subtitle = "When someone reacts to your message") }
        }
        Section(title = "Calls") {
            row { SwitchRow(TnIcon.CALL, "Ring for incoming calls", n.calls.ring, { viewModel.update { it.copy(calls = it.calls.copy(ring = !it.calls.ring)) } }, subtitle = "Off declines every call automatically") }
            row { SwitchRow(TnIcon.VIBRATE, "Vibrate while ringing", n.calls.vibrate, { viewModel.update { it.copy(calls = it.calls.copy(vibrate = !it.calls.vibrate)) } }) }
            row { SwitchRow(TnIcon.CALL_MISSED, "Missed call notifications", n.calls.missedNotification, { viewModel.update { it.copy(calls = it.calls.copy(missedNotification = !it.calls.missedNotification)) } }) }
        }
        Section(title = "In-app") {
            row { SwitchRow(TnIcon.VIBRATE, "Vibrate on new message", n.inApp.vibrate, { viewModel.update { it.copy(inApp = it.inApp.copy(vibrate = !it.inApp.vibrate)) } }, subtitle = "While the app is open") }
        }
        Section(title = "Quiet hours") {
            row { SwitchRow(TnIcon.CLOCK, "Quiet hours", n.quietHours.enabled, { viewModel.update { it.copy(quietHours = it.quietHours.copy(enabled = !it.quietHours.enabled)) } }, subtitle = "Silence message alerts in this window; calls still ring") }
            if (n.quietHours.enabled) {
                row { SelectMenuRow("From", hourOptions, n.quietHours.fromHour.toString()) { viewModel.openMenu(QUIET_FROM) } }
                row { SelectMenuRow("To", hourOptions, n.quietHours.toHour.toString()) { viewModel.openMenu(QUIET_TO) } }
            }
        }
        Section(title = "System", footnote = "These settings only make alerts quieter. Your phone’s sound profile and Do Not Disturb are always respected.") {
            row { SettingRow(TnIcon.BELL_OFF, "Tunnel notification", subtitle = "Keeps the app alive in the background; silence or minimise it in Android's settings", onClick = viewModel::openTunnelNotificationSettings) }
            row { SettingRow(TnIcon.SETTINGS, "Android notification settings", subtitle = "Channels, tones, Do Not Disturb exceptions", onClick = viewModel::openSystemSettings) }
            row { SettingRow(TnIcon.RETRY, "Reset to defaults", onClick = viewModel::resetToDefaults) }
        }
    }
    SelectMenu("From", hourOptions, n.quietHours.fromHour.toString(), state.openMenuKey == QUIET_FROM, viewModel::setQuietFrom, viewModel::closeMenu)
    SelectMenu("To", hourOptions, n.quietHours.toHour.toString(), state.openMenuKey == QUIET_TO, viewModel::setQuietTo, viewModel::closeMenu)
}

private const val QUIET_FROM = "quiet-from"
private const val QUIET_TO = "quiet-to"

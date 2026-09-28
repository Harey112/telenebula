package com.telenebula.app.ui.screens.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.TimePickerModal
import com.telenebula.app.ui.fragments.ValueRow
import com.telenebula.app.ui.fragments.formatTime
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.fragments.SwitchRow
import com.telenebula.app.ui.icons.TnIcon

@Composable
fun NotificationsScreen(viewModel: NotificationsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val n = state.prefs
    Screen(title = "Notifications and sounds", onBack = viewModel::goBack) {
        Section(title = "Messages", help = "Show notifications, preview and sound are this phone's own; Dex browsers have their own in their Settings. The other switches affect only this phone. With sender name off the alert says “TeleNebula”; with preview off it says “New message”.") {
            row { SwitchRow(TnIcon.CHATS, "Show notifications", state.isEnabled, viewModel::toggleEnabled) }
            row { SwitchRow(TnIcon.PERSON, "Show sender name", n.messages.showSender, { viewModel.update { it.copy(messages = it.messages.copy(showSender = !it.messages.showSender)) } }) }
            row { SwitchRow(TnIcon.LIST, "Message preview", state.isPreview, viewModel::togglePreview) }
            row { SwitchRow(TnIcon.SPEAKER, "Sound", state.isSound, viewModel::toggleSound) }
            row { SwitchRow(TnIcon.VIBRATE, "Vibrate", n.messages.vibrate, { viewModel.update { it.copy(messages = it.messages.copy(vibrate = !it.messages.vibrate)) } }) }
            row { SwitchRow(TnIcon.BELL, "Pop-up notifications", n.messages.popup, { viewModel.update { it.copy(messages = it.messages.copy(popup = !it.messages.popup)) } }) }
            row { SwitchRow(TnIcon.EMOJI, "Reaction notifications", n.messages.reactions, { viewModel.update { it.copy(messages = it.messages.copy(reactions = !it.messages.reactions)) } }) }
        }
        Section(title = "Calls", help = "With ringing off, every incoming call is declined and logged as declined.") {
            row { SwitchRow(TnIcon.CALL, "Ring for incoming calls", n.calls.ring, { viewModel.update { it.copy(calls = it.calls.copy(ring = !it.calls.ring)) } }) }
            row { SwitchRow(TnIcon.VIBRATE, "Vibrate while ringing", n.calls.vibrate, { viewModel.update { it.copy(calls = it.calls.copy(vibrate = !it.calls.vibrate)) } }) }
            row { SwitchRow(TnIcon.CALL_MISSED, "Missed call notifications", n.calls.missedNotification, { viewModel.update { it.copy(calls = it.calls.copy(missedNotification = !it.calls.missedNotification)) } }) }
        }
        Section(title = "In-app", help = "Vibrates when a message arrives in the chat you have open; other chats get a normal notification.") {
            row { SwitchRow(TnIcon.VIBRATE, "Vibrate on new message", n.inApp.vibrate, { viewModel.update { it.copy(inApp = it.inApp.copy(vibrate = !it.inApp.vibrate)) } }) }
        }
        Section(title = "Quiet hours", help = "Silences message notifications and in-app vibration in this window; calls still ring.") {
            row { SwitchRow(TnIcon.CLOCK, "Quiet hours", n.quietHours.enabled, { viewModel.update { it.copy(quietHours = it.quietHours.copy(enabled = !it.quietHours.enabled)) } }) }
            if (n.quietHours.enabled) {
                row { ValueRow("From", formatTime(n.quietHours.fromHour, n.quietHours.fromMinute)) { viewModel.openMenu(QUIET_FROM) } }
                row { ValueRow("To", formatTime(n.quietHours.toHour, n.quietHours.toMinute)) { viewModel.openMenu(QUIET_TO) } }
            }
        }
        Section(title = "System", help = "These settings never override your phone's sound profile or Do Not Disturb. The tunnel notification keeps the app running in the background; silence or minimise it in Android's settings. Reset to defaults resets this screen; Dex browsers keep their own switches.") {
            row { SettingRow(TnIcon.BELL_OFF, "Tunnel notification", onClick = viewModel::openTunnelNotificationSettings) }
            row { SettingRow(TnIcon.SETTINGS, "Android notification settings", onClick = viewModel::openSystemSettings) }
            row { SettingRow(TnIcon.RETRY, "Reset to defaults", onClick = viewModel::resetToDefaults) }
        }
    }
    TimePickerModal(
        isVisible = state.openMenuKey == QUIET_FROM,
        title = "Quiet hours start",
        hour = n.quietHours.fromHour,
        minute = n.quietHours.fromMinute,
        onCancel = viewModel::closeMenu,
        onConfirm = { hour, minute ->
            viewModel.closeMenu()
            viewModel.setQuietFrom(hour, minute)
        },
    )
    TimePickerModal(
        isVisible = state.openMenuKey == QUIET_TO,
        title = "Quiet hours end",
        hour = n.quietHours.toHour,
        minute = n.quietHours.toMinute,
        onCancel = viewModel::closeMenu,
        onConfirm = { hour, minute ->
            viewModel.closeMenu()
            viewModel.setQuietTo(hour, minute)
        },
    )
}

private const val QUIET_FROM = "quiet-from"
private const val QUIET_TO = "quiet-to"

package com.telenebula.app.ui.screens.callprefs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.fragments.SwitchRow
import com.telenebula.app.ui.icons.TnIcon

@Composable
fun CallPrefsScreen(viewModel: CallPrefsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Screen(title = "Calls", onBack = viewModel::goBack) {
        Section(footnote = "Ringing, vibration and missed-call alerts live under Notifications and sounds.") {
            row { SwitchRow(TnIcon.SPEAKER, "Loudspeaker on video calls", state.isVideoSpeakerDefault, viewModel::toggleVideoSpeakerDefault, subtitle = "Start video calls with the speaker on") }
            row {
                SettingRow(
                    TnIcon.VIDEO,
                    "Floating call window",
                    subtitle = if (state.isOverlayPermitted) "Allowed — video calls float over other apps" else "Allow “Display over other apps” in system settings",
                    onClick = viewModel::openOverlayPermission,
                )
            }
        }
    }
}

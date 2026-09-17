package com.telenebula.app.ui.screens.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.InfoField
import com.telenebula.app.ui.fragments.PrimaryButton
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.fragments.SwitchRow
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun UpdatesScreen(viewModel: UpdatesViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TnTheme.colors
    Screen(title = "Updates", onBack = viewModel::goBack) {
        Section(title = "Installed") {
            row { InfoField("Version", state.appVersion) }
            row { InfoField("Build", state.buildNumber) }
            state.latestVersion?.let { v -> row { InfoField("Latest release", v, valueColor = if (state.isUpdateAvailable) colors.accent else colors.success) } }
        }
        if (state.isUpdateAvailable) {
            Section(title = "Update available", footnote = "The APK is fetched from the GitHub release for this device and handed to Android's installer. Your data stays.") {
                row { UpdateAction(state, viewModel::downloadAndInstall) }
            }
        }
        Section(title = "Checking", footnote = "Releases are published on GitHub. A daily check runs while the app is alive and notifies you once per new release.") {
            row { SwitchRow(TnIcon.CLOCK, "Check daily", state.isDailyCheckEnabled, viewModel::toggleDailyCheck, subtitle = "Last checked: ${state.lastCheckedText}") }
            row { SettingRow(TnIcon.RETRY, "Check now", subtitle = state.lastError?.let { "Last check failed: $it" } ?: "Compares this build with the newest release", onClick = viewModel::checkForUpdates) }
            row { SettingRow(TnIcon.LINK, "Open the releases page", onClick = viewModel::openReleases) }
        }
    }
}

@Composable
private fun UpdateAction(state: UpdatesUiState, onDownload: () -> Unit) {
    val colors = TnTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(TnSpace.lg), verticalArrangement = Arrangement.spacedBy(TnSpace.md)) {
        Text("Version ${state.latestVersion} is ready for you. You have ${state.appVersion}.", style = TnType.body, color = colors.text)
        when (val d = state.download) {
            Download.Idle -> PrimaryButton("Download and install", onDownload)
            is Download.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TnSpace.md)) {
                CircularProgressIndicator(progress = { d.fraction }, modifier = Modifier.size(22.dp), color = colors.accent, trackColor = colors.hairline)
                Text("Downloading… ${(d.fraction * 100).toInt()}%", style = TnType.small, color = colors.textMuted)
            }
            is Download.Ready -> PrimaryButton("Install", onDownload)
        }
    }
}

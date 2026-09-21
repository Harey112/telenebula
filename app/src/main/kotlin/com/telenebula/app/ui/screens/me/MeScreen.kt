package com.telenebula.app.ui.screens.me

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.nav.ArchivedChats
import com.telenebula.app.nav.Dex
import com.telenebula.app.nav.Settings
import com.telenebula.app.nav.Status
import com.telenebula.app.ui.fragments.DotBadge
import com.telenebula.app.ui.fragments.ProfileCard
import com.telenebula.app.ui.fragments.QrSheet
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.fragments.TunnelCard
import com.telenebula.app.ui.icons.TnIcon

@Composable
fun MeScreen(viewModel: MeViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Screen(title = "Me") {
        ProfileCard(name = state.certName, overlayIp = state.overlayIp, isOnline = state.isOnline, onShowQr = viewModel::openQr)
        TunnelCard(isRunning = state.isVpnRunning, isBusy = state.isBusy, onToggle = viewModel::toggleVpn)
        Menu(state, viewModel)
    }
    QrSheet(isVisible = state.isQrOpen, value = state.qrValue, name = state.certName, detail = state.overlayIp, onClose = viewModel::closeQr)
}

@Composable
private fun Menu(state: MeUiState, actions: MeActions) {
    Section {
        row {
            SettingRow(
                TnIcon.SETTINGS,
                "Settings",
                subtitle = if (state.hasUpdate) "An app update is available" else "Account, network, preferences, app",
                onClick = { actions.open(Settings) },
                trailing = if (state.hasUpdate) ({ DotBadge("Update available") }) else null,
            )
        }
        row { SettingRow(TnIcon.CIRCLE, "Status", subtitle = "What contacts see when they check on you", onClick = { actions.open(Status) }) }
        row { SettingRow(TnIcon.DESKTOP, "Dex", subtitle = state.dexLabel, onClick = { actions.open(Dex) }) }
        row {
            SettingRow(
                TnIcon.ARCHIVE,
                "Archived chats",
                subtitle = when (state.archivedCount) {
                    0 -> "Nothing archived"
                    1 -> "1 chat"
                    else -> "${state.archivedCount} chats"
                },
                onClick = { actions.open(ArchivedChats) },
            )
        }
    }
}

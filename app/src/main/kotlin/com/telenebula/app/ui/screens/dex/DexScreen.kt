package com.telenebula.app.ui.screens.dex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.EditNameModal
import com.telenebula.app.ui.fragments.ErrorBanner
import com.telenebula.app.ui.fragments.InfoField
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SelectMenu
import com.telenebula.app.ui.fragments.SelectMenuRow
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.fragments.SwitchRow
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun DexScreen(viewModel: DexViewModel) {
    val s by viewModel.uiState.collectAsStateWithLifecycle()
    Screen(title = "Dex", onBack = viewModel::goBack) {
        s.failure?.let { ErrorBanner(it, modifier = Modifier.padding(horizontal = TnSpace.lg, vertical = TnSpace.md)) }
        Section(footnote = "Your chats and calls in a browser on the same Wi‑Fi, hotspot, USB or Bluetooth tethering. Everything still runs on this phone.") {
            row { SwitchRow(TnIcon.DESKTOP, "Dex", s.isEnabled, viewModel::toggleEnabled, subtitle = s.statusLabel) }
        }
        Section(title = "Login") {
            row { SettingRow(TnIcon.PERSON, "Username", subtitle = s.username.ifEmpty { "Not set" }, onClick = viewModel::editUsername) }
            row { SettingRow(TnIcon.LOCK, "Password", subtitle = if (s.hasPassword) "Set · tap to change" else "Not set", onClick = viewModel::editPassword) }
            row { SelectMenuRow("Client limit", viewModel.clientOptions, s.maxClientsKey) { viewModel.openMenu(DexViewModel.MENU_CLIENTS) } }
        }
        Addresses(s, viewModel)
        if (s.fingerprint.isNotEmpty()) {
            Section(title = "Certificate", footnote = "The browser warns once about this self-signed certificate. Continue only if it shows this fingerprint.") {
                row { InfoField("SHA-256 fingerprint", s.fingerprint, isMono = true, isSmall = true) }
            }
        }
        Clients(s)
    }
    EditNameModal(
        isVisible = s.editor == DexEditor.Username,
        value = s.editorValue,
        onChange = viewModel::setEditorValue,
        onCancel = viewModel::cancelEditor,
        onSave = viewModel::saveEditor,
        title = "Username",
        placeholder = "Username",
    )
    EditNameModal(
        isVisible = s.editor == DexEditor.Password,
        value = s.editorValue,
        onChange = viewModel::setEditorValue,
        onCancel = viewModel::cancelEditor,
        onSave = viewModel::saveEditor,
        title = "Password",
        placeholder = "At least ${DexViewModel.MIN_PASSWORD_CHARS} characters",
    )
    SelectMenu("Client limit", viewModel.clientOptions, s.maxClientsKey, s.openMenuKey == DexViewModel.MENU_CLIENTS, viewModel::setMaxClients, viewModel::closeMenu)
}

@Composable
private fun Addresses(s: DexUiState, actions: DexActions) {
    val colors = TnTheme.colors
    Section(title = "Open on your computer", footnote = if (s.isEnabled && s.urls.isNotEmpty()) "Tap an address to copy it." else null) {
        when {
            !s.isEnabled -> row { Text("Turn Dex on to get an address.", style = TnType.body, color = colors.textMuted, modifier = Modifier.padding(TnSpace.lg)) }
            s.urls.isEmpty() -> row {
                Text(
                    "No way in yet. Join a Wi‑Fi network, or turn on a hotspot, USB tethering or Bluetooth tethering.",
                    style = TnType.body,
                    color = colors.textMuted,
                    modifier = Modifier.padding(TnSpace.lg),
                )
            }
            else -> for ((label, url) in s.urls) {
                row {
                    key(url) {
                        Box(modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = { actions.copyUrl(url) })) { InfoField(label, url, isMono = true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Clients(s: DexUiState) {
    if (!s.isEnabled) return
    Section(title = "Connected clients (${s.clients.size})") {
        if (s.clients.isEmpty()) {
            row { Text("Nobody is logged in.", style = TnType.body, color = TnTheme.colors.textMuted, modifier = Modifier.padding(TnSpace.lg)) }
        }
        for (client in s.clients) {
            row { key(client.id) { SettingRow(TnIcon.DESKTOP, client.label, subtitle = client.detail) } }
        }
    }
}

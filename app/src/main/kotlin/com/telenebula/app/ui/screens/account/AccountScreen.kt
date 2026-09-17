package com.telenebula.app.ui.screens.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.model.CertExpiryLevel
import com.telenebula.app.ui.fragments.InfoField
import com.telenebula.app.ui.fragments.RowTone
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnTheme

@Composable
fun AccountScreen(viewModel: AccountViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TnTheme.colors
    val statusColor = when (state.certStatusLevel) {
        CertExpiryLevel.OK -> colors.success
        CertExpiryLevel.WARNING -> colors.warning
        CertExpiryLevel.EXPIRED -> colors.danger
    }
    Screen(title = "Account", onBack = viewModel::goBack) {
        Section(title = "Identity") {
            row { InfoField("Mobile (Nebula IPv6)", state.overlayIp, isMono = true) }
            row { InfoField("Username (from host certificate)", "@${state.certName}", isMono = true) }
        }
        Section(title = "Certificate") {
            row { InfoField("Validity", state.certStatusText, valueColor = statusColor) }
            row { SettingRow(TnIcon.RETRY, "Renew certificate", subtitle = "Upload a re-issued host.crt and host.key, keep your chats", onClick = viewModel::openRenewCertificate) }
        }
        Section(title = "Backup") {
            row {
                SettingRow(
                    TnIcon.FOLDER,
                    if (state.isBackupBusy) "Working…" else "Export backup",
                    subtitle = "Contacts, chats, media and settings as one file; never your private key",
                    onClick = if (state.isBackupBusy) null else viewModel::exportBackup,
                )
            }
            row {
                SettingRow(
                    TnIcon.UNARCHIVE,
                    "Restore from backup",
                    subtitle = "Adds what the file has; nothing on this device is removed",
                    onClick = if (state.isBackupBusy) null else viewModel::restoreBackup,
                )
            }
        }
        Section(title = "Danger zone") {
            row { SettingRow(TnIcon.CLEAR, "Delete all chats", subtitle = "Removes every message and media file; keeps contacts and identity", onClick = viewModel::confirmDeleteAllChats) }
            row { SettingRow(TnIcon.TRASH, "Reset identity", subtitle = "Delete certs & key from this device", onClick = viewModel::confirmReset, tone = RowTone.DANGER) }
        }
    }
}

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
        Section(title = "Certificate", help = "Renew certificate takes a re-issued host.crt and host.key for this device; contacts and chats are kept.") {
            row { InfoField("Validity", state.certStatusText, valueColor = statusColor) }
            row { SettingRow(TnIcon.RETRY, "Renew certificate", onClick = viewModel::openRenewCertificate) }
        }
        Section(title = "Backup", help = "Export writes contacts, chats, media and settings to one file, never your private key. Restore adds the file's contacts, chats and media and removes nothing; its settings are used only on a phone that has none yet.") {
            row {
                SettingRow(
                    TnIcon.FOLDER,
                    if (state.isBackupBusy) "Working…" else "Export backup",
                    onClick = if (state.isBackupBusy) null else viewModel::exportBackup,
                )
            }
            row {
                SettingRow(
                    TnIcon.UNARCHIVE,
                    "Restore from backup",
                    onClick = if (state.isBackupBusy) null else viewModel::restoreBackup,
                )
            }
        }
        Section(title = "Danger zone", help = "Delete all chats removes every message and media file but keeps contacts and your identity. Reset identity deletes the certificates and key from this phone.") {
            row { SettingRow(TnIcon.CLEAR, "Delete all chats", onClick = viewModel::confirmDeleteAllChats) }
            row { SettingRow(TnIcon.TRASH, "Reset identity", onClick = viewModel::confirmReset, tone = RowTone.DANGER) }
        }
    }
}

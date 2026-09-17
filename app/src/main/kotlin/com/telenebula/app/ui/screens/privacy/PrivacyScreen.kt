package com.telenebula.app.ui.screens.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.nav.Blocked
import com.telenebula.app.ui.fragments.InfoField
import com.telenebula.app.ui.fragments.RowTone
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SelectRow
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.fragments.SwitchRow
import com.telenebula.app.ui.icons.TnIcon

@Composable
fun PrivacyScreen(viewModel: PrivacyViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Screen(title = "Security & privacy", onBack = viewModel::goBack) {
        Section(title = "Encryption") {
            row { InfoField("Transport encryption", "Nebula mesh — Noise IK with mutual certificates, AES") }
            row { InfoField("Your identity fingerprint", state.certFingerprint, isMono = true, isSmall = true) }
            row { InfoField("Certificate expires", state.certExpiry) }
        }
        Section(title = "App lock") {
            row {
                SwitchRow(
                    icon = TnIcon.LOCK,
                    title = "Require device unlock",
                    checked = state.isAppLockEnabled && state.canUseAppLock,
                    onToggle = { if (state.canUseAppLock) viewModel.toggleAppLock() },
                    subtitle = if (state.canUseAppLock) "Fingerprint, face or device PIN" else "No screen lock is set up on this phone",
                    isEnabled = state.canUseAppLock,
                )
            }
            if (state.isAppLockEnabled && state.canUseAppLock) {
                row { SelectRow("Lock after leaving the app", viewModel.appLockAfterOptions, state.appLockAfterKey, viewModel::setAppLockAfter) }
            }
        }
        Section(title = "Privacy") {
            row { SwitchRow(TnIcon.EYE, "Send read receipts", state.sendReadReceipts, viewModel::toggleReadReceipts, subtitle = "Let contacts see when you have read their messages") }
            row { SwitchRow(TnIcon.PENCIL, "Send typing indicators", state.sendTypingIndicators, viewModel::toggleTypingIndicators, subtitle = "Show contacts when you are typing") }
            row { SwitchRow(TnIcon.LOCK, "Block screenshots", state.isScreenshotBlocked, viewModel::toggleScreenshotBlock, subtitle = "Hide the app from screen capture and recents") }
        }
        Section(title = "Blocked", footnote = "Peers are authenticated by your nebula CA; only devices holding a certificate it signed can reach this app at all.") {
            row {
                SettingRow(
                    TnIcon.BLOCK,
                    "Blocked contacts",
                    subtitle = if (state.blockedCount == 0) "None" else "${state.blockedCount} blocked",
                    onClick = viewModel::openBlocked,
                    tone = RowTone.DANGER,
                )
            }
        }
    }
}

package com.telenebula.app.ui.screens.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.nav.Blocked
import com.telenebula.app.ui.fragments.InfoField
import com.telenebula.app.ui.fragments.RowTone
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SelectMenu
import com.telenebula.app.ui.fragments.SelectMenuRow
import com.telenebula.app.ui.fragments.SelectMenuRow
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
                row { SelectMenuRow("Lock after leaving the app", viewModel.appLockAfterOptions, state.appLockAfterKey) { viewModel.openMenu(LOCK_AFTER) } }
            }
        }
        Section(title = "Privacy", help = "Read receipts let contacts see when you have read their messages, and typing indicators when you are writing. These and how covered messages are revealed are this phone's own; Dex has its own in the browser's Settings. Block screenshots hides the app from screen capture and recents on this phone only.") {
            row { SwitchRow(TnIcon.EYE, "Send read receipts", state.sendReadReceipts, viewModel::toggleReadReceipts) }
            row { SwitchRow(TnIcon.PENCIL, "Send typing indicators", state.sendTypingIndicators, viewModel::toggleTypingIndicators) }
            row { SwitchRow(TnIcon.LOCK, "Block screenshots", state.isScreenshotBlocked, viewModel::toggleScreenshotBlock) }
            row { SelectMenuRow("Reveal covered messages", viewModel.coverGateOptions, state.coverGateKey) { viewModel.openMenu(COVER_GATE) } }
        }
        Section(title = "Blocked", help = "Peers are authenticated by your nebula CA: only devices with a certificate it signed can message or call you. Blocked contacts can do neither.") {
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
    SelectMenu("Lock after leaving the app", viewModel.appLockAfterOptions, state.appLockAfterKey, state.openMenuKey == LOCK_AFTER, viewModel::setAppLockAfter, viewModel::closeMenu)
    SelectMenu("Reveal covered messages", viewModel.coverGateOptions, state.coverGateKey, state.openMenuKey == COVER_GATE, viewModel::setCoverGate, viewModel::closeMenu)
}

private const val LOCK_AFTER = "lock-after"
private const val COVER_GATE = "cover-gate"

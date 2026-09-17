package com.telenebula.app.ui.screens.renew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.ErrorBanner
import com.telenebula.app.ui.fragments.FileUploadRow
import com.telenebula.app.ui.fragments.InfoField
import com.telenebula.app.ui.fragments.PrimaryButton
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun RenewCertificateScreen(viewModel: RenewCertificateViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TnTheme.colors
    Screen(title = "Renew certificate", onBack = viewModel::goBack) {
        Section(
            title = "Current certificate",
            footnote = "Upload the re-issued credentials for this device. Your contacts, chats and settings are kept; the tunnel restarts with the new certificate.",
        ) {
            row { InfoField("Username", "@${state.currentName}", isMono = true) }
            row { InfoField("Nebula IPv6", state.currentIp, isMono = true) }
            row { InfoField("Validity", state.currentExpiry) }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg).padding(top = TnSpace.xl)) {
            Text("NEW CREDENTIALS", style = TnType.small, color = colors.textMuted, modifier = Modifier.padding(horizontal = TnSpace.xs))
            FileUploadRow("Host certificate (host.crt)", state.hasCert, viewModel::pickCert, detail = state.certDetail)
            FileUploadRow("Host private key (host.key)", state.hasKey, viewModel::pickKey)
            FileUploadRow("CA certificate (ca.crt) — optional", state.hasCa, viewModel::pickCa, detail = state.caDetail)
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg).padding(top = TnSpace.xl),
            verticalArrangement = Arrangement.spacedBy(TnSpace.sm),
        ) {
            for (warning in state.warnings) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(TnRadius.md))
                        .background(lerp(colors.warning, colors.surface, 0.86f))
                        .padding(TnSpace.md),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(TnSpace.sm),
                ) {
                    Icon(TnIcon.SHIELD, tint = colors.warning, size = 18.dp)
                    Text(warning, style = TnType.small, color = colors.text, modifier = Modifier.weight(1f))
                }
            }
            ErrorBanner(state.error)
            PrimaryButton(label = "Replace certificate & reconnect", onClick = viewModel::apply, isEnabled = state.canApply, isBusy = state.isBusy)
        }
    }
}

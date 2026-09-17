package com.telenebula.app.ui.screens.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.EndpointFields
import com.telenebula.app.ui.fragments.ErrorBanner
import com.telenebula.app.ui.fragments.FileUploadRow
import com.telenebula.app.ui.fragments.PrimaryButton
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.TnTextField
import com.telenebula.app.ui.root.NebulaAdvancedSettings
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun SetupScreen(viewModel: SetupViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Screen(hasKeyboard = true) {
        SetupIntro()
        CredentialsSection(state, viewModel)
        LighthouseSection(state, viewModel)
        NebulaAdvancedSettings()
        SetupSubmit(state, viewModel)
    }
}

@Composable
private fun SetupIntro() {
    val colors = TnTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg).padding(top = TnSpace.xl)) {
        Text("TeleNebula", style = TnType.display, color = colors.text)
        Text(
            "P2P messaging and calls over your Nebula overlay. Your IPv6 address is your number and the name in your host certificate is your username. Upload the credentials issued for this device (ca.crt, host.crt, host.key).",
            style = TnType.body,
            color = colors.textMuted,
            modifier = Modifier.padding(top = TnSpace.sm),
        )
    }
}

@Composable
private fun CredentialsSection(state: SetupUiState, actions: SetupActions) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg).padding(top = TnSpace.xl)) {
        Text("NEBULA CREDENTIALS", style = TnType.small, color = TnTheme.colors.textMuted, modifier = Modifier.padding(horizontal = TnSpace.xs))
        FileUploadRow("CA certificate (ca.crt)", state.hasCa, actions::pickCa, detail = state.caDetail)
        FileUploadRow("Host certificate (host.crt)", state.hasCert, actions::pickCert, detail = state.certDetail)
        FileUploadRow("Host private key (host.key)", state.hasKey, actions::pickKey)
    }
}

@Composable
private fun LighthouseSection(state: SetupUiState, actions: SetupActions) {
    Section(title = "Lighthouse") {
        row {
            Column(modifier = Modifier.fillMaxWidth().padding(start = TnSpace.lg, end = TnSpace.lg, bottom = TnSpace.lg, top = TnSpace.xs)) {
                TnTextField(
                    value = state.lhNebulaIp,
                    onChange = actions::setLhNebulaIp,
                    label = "Lighthouse nebula IPv6",
                    placeholder = "fd00:1234:5678::1",
                    isMono = true,
                    hasAutoCapitalize = false,
                )
                EndpointFields(host = state.lhHost, port = state.lhPort, onHost = actions::setLhHost, onPort = actions::setLhPort, hostLabel = "Lighthouse public IP")
            }
        }
    }
}

@Composable
private fun SetupSubmit(state: SetupUiState, actions: SetupActions) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg).padding(top = TnSpace.xl)) {
        ErrorBanner(state.error)
        PrimaryButton(label = "Create identity & connect", onClick = actions::create, isEnabled = state.canCreate, isBusy = state.isBusy, modifier = Modifier.padding(top = TnSpace.sm))
    }
}

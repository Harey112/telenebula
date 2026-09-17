package com.telenebula.app.ui.screens.lighthouse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.EndpointFields
import com.telenebula.app.ui.fragments.PrimaryButton
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.TnTextField
import com.telenebula.app.ui.root.NebulaAdvancedSettings
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun LighthouseScreen(viewModel: LighthouseViewModel) {
    val s by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TnTheme.colors
    Screen(title = "Lighthouse", onBack = viewModel::goBack, hasKeyboard = true) {
        Text(
            "The lighthouse is the public node peers use to discover each other's addresses. Traffic flows directly between devices when their networks allow it, otherwise through the lighthouse as a relay.",
            style = TnType.small,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = TnSpace.xl).padding(top = TnSpace.sm),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TnSpace.lg)
                .clip(RoundedCornerShape(TnRadius.lg))
                .background(colors.surface)
                .padding(start = TnSpace.lg, end = TnSpace.lg, bottom = TnSpace.lg, top = TnSpace.xs),
        ) {
            TnTextField(s.nebulaIp, viewModel::setNebulaIp, label = "Nebula IPv6", placeholder = "fd00:1234:5678::1", isMono = true, hasAutoCapitalize = false)
            EndpointFields(host = s.publicHost, port = s.publicPort, onHost = viewModel::setPublicHost, onPort = viewModel::setPublicPort)
        }
        NebulaAdvancedSettings()
        PrimaryButton("Save", viewModel::save, isEnabled = s.canSave, isBusy = s.isBusy, modifier = Modifier.padding(horizontal = TnSpace.lg, vertical = TnSpace.lg))
    }
}

package com.telenebula.app.ui.screens.network

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.InfoField
import com.telenebula.app.ui.fragments.PeerRowItem
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.fragments.SwitchRow
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun NetworkScreen(viewModel: NetworkViewModel) {
    val s by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TnTheme.colors
    Screen(
        title = "Network",
        onBack = viewModel::goBack,
        trailing = {
            Icon(TnIcon.RETRY, tint = colors.text, size = 20.dp, contentDescription = "Refresh", modifier = Modifier.clickable(role = Role.Button, onClick = viewModel::refresh).padding(4.dp))
        },
    ) {
        Section(title = "Status") {
            row { InfoField("Nebula tunnel", if (s.isVpnRunning) "Connected" else "Disconnected") }
            row { InfoField("Tunnel uptime", s.tunnelUptime) }
            row { InfoField("Messaging engine uptime", s.engineUptime) }
            row { InfoField("Peers with a live message link", s.connectedCount.toString()) }
            row { SwitchRow(TnIcon.RETRY, "Start when the phone starts", s.isStartOnBoot, viewModel::toggleStartOnBoot, subtitle = "Also after an update; the tunnel connects on its own") }
        }
        Section(title = "My node") {
            row { InfoField("Node ID (certificate name)", "@${s.username}", isMono = true) }
            row { InfoField("Overlay address", s.overlayIp, isMono = true) }
            row { InfoField("Overlay network(s)", s.networks, isMono = true, isSmall = true) }
            row { InfoField("Ports and MTU", s.ports, isMono = true) }
        }
        Section(title = "Lighthouse") {
            row {
                SettingRow(
                    TnIcon.SHIELD,
                    s.lighthouseIp.ifEmpty { "Not configured" },
                    subtitle = "${s.lighthouseUnderlay} · ${s.lighthouseStatus}",
                    onClick = viewModel::openLighthouse,
                )
            }
            row { InfoField("Peers behind hard NATs", s.relayStatus) }
        }
        Section(
            title = "Peers (${s.peers.size})",
            footnote = if (s.pendingHandshakes > 0) "${s.pendingHandshakes} handshake(s) in progress" else null,
        ) {
            if (s.peers.isEmpty()) {
                row {
                    Text(
                        if (s.isVpnRunning) "No tunnels established yet." else "Tunnel is off.",
                        style = TnType.body,
                        color = colors.textMuted,
                        modifier = Modifier.padding(TnSpace.lg),
                    )
                }
            }
            for (peer in s.peers) {
                row { key(peer.ip) { PeerRowItem(peer, viewModel::openPeer) } }
            }
        }
        Section(title = "Data usage (messaging)") {
            row { InfoField("Total over the message link", "↑ ${s.bytesSent}   ↓ ${s.bytesReceived}", isMono = true) }
            row { InfoField("Outbox", "${s.pendingActions} pending · ${s.failedActions} failed") }
        }
        Section(title = "Advanced") {
            row { SwitchRow(TnIcon.SETTINGS, "Developer mode", s.isDeveloperMode, viewModel::toggleDeveloperMode, subtitle = "Show firewall, handshakes and tunnel controls") }
            if (s.isDeveloperMode) {
                row { SwitchRow(TnIcon.LIST, "Verbose nebula logging", s.isVerboseLogging, viewModel::toggleVerboseLogging, subtitle = "debug level, applied live") }
                row { SettingRow(TnIcon.RETRY, "Reconnect tunnel", subtitle = "Stop and start nebula", onClick = viewModel::reconnectTunnel) }
                row { SettingRow(TnIcon.FILE, "Nebula log", subtitle = "In Diagnostics", onClick = viewModel::openDiagnostics) }
            }
        }
        if (s.isDeveloperMode) {
            Section(title = "Firewall") {
                row {
                    Column(modifier = Modifier.fillMaxWidth().padding(TnSpace.lg)) {
                        for (rule in s.firewallRules) {
                            Text(rule, style = TnType.small.copy(fontFamily = FontFamily.Monospace), color = colors.text)
                        }
                    }
                }
            }
        }
    }
}

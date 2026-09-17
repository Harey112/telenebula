package com.telenebula.app.ui.screens.contact

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.platform.Format
import com.telenebula.app.ui.fragments.ActionButton
import com.telenebula.app.ui.fragments.Avatar
import com.telenebula.app.ui.fragments.CallLogRow
import com.telenebula.app.ui.fragments.ContactEditModal
import com.telenebula.app.ui.fragments.ErrorBanner
import com.telenebula.app.ui.fragments.InfoField
import com.telenebula.app.ui.fragments.RowTone
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnRow
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun ContactScreen(viewModel: ContactViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onShown() }
    Screen {
        ContactHeader(state, viewModel)
        Section {
            row { InfoField("Mobile (Nebula IPv6)", state.ip, isMono = true) }
            if (state.notes.isNotEmpty()) row { InfoField("Notes", state.notes) }
            row { InfoField("Contact history", state.addedAtText) }
        }
        CallsSection(state, viewModel)
        AdvancedSection(state, viewModel)
        Section(title = "Danger zone") {
            row {
                SettingRow(
                    TnIcon.BLOCK,
                    if (state.isBlocked) "Unblock" else "Block",
                    subtitle = if (state.isBlocked) "Messages and calls are dropped" else "Drop their messages and calls",
                    onClick = viewModel::toggleBlock,
                    tone = if (state.isBlocked) RowTone.DEFAULT else RowTone.DANGER,
                )
            }
            row { SettingRow(TnIcon.TRASH, "Delete contact", subtitle = "Removes the contact and the chat", onClick = viewModel::confirmDelete, tone = RowTone.DANGER) }
        }
    }
    val edit = state.edit
    ContactEditModal(
        isVisible = edit != null,
        address = edit?.address.orEmpty(),
        onAddressChange = viewModel::setEditAddress,
        nickname = edit?.nickname.orEmpty(),
        onNicknameChange = viewModel::setEditNickname,
        notes = edit?.notes.orEmpty(),
        onNotesChange = viewModel::setEditNotes,
        onCancel = viewModel::cancelEdit,
        onSave = viewModel::saveEdit,
    )
}

@Composable
private fun pingColor(tint: PingTint) = when (tint) {
    PingTint.ONLINE -> TnTheme.colors.success
    PingTint.REACHABLE -> TnTheme.colors.info
    PingTint.OFFLINE -> TnTheme.colors.danger
    PingTint.NONE -> null
}

/** Back/edit bar, avatar block, then Ping · Message · Call · Video. */
@Composable
private fun ContactHeader(state: ContactUiState, actions: ContactActions) {
    val colors = TnTheme.colors
    Row(modifier = Modifier.fillMaxWidth().height(TnRow.height).padding(horizontal = TnSpace.lg), verticalAlignment = Alignment.CenterVertically) {
        Icon(TnIcon.BACK, tint = colors.text, contentDescription = "Back", modifier = Modifier.clickable(role = Role.Button, onClick = actions::goBack))
        Spacer(modifier = Modifier.weight(1f))
        Icon(TnIcon.PENCIL, tint = colors.text, size = 22.dp, contentDescription = "Edit contact", modifier = Modifier.clickable(role = Role.Button, onClick = actions::beginEdit))
    }
    state.error?.let { ErrorBanner(it, modifier = Modifier.padding(horizontal = TnSpace.lg), onDismiss = actions::clearError) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = TnSpace.sm), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TnSpace.xs)) {
        Avatar(state.name, size = 96.dp)
        Text(state.name, style = TnType.heading, color = colors.text, modifier = Modifier.padding(top = TnSpace.sm))
        if (state.nickname.isNotEmpty()) Text("“${state.nickname}”", style = TnType.body, color = colors.textMuted)
        Text(state.presenceText, style = TnType.small, color = colors.accent)
    }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg).padding(top = TnSpace.xl), horizontalArrangement = Arrangement.spacedBy(TnSpace.sm)) {
        ActionButton(TnIcon.PING, if (state.isPinging) "Pinging…" else "Ping", actions::ping, Modifier.weight(1f), tint = pingColor(state.pingTint), isBusy = state.isPinging, isEnabled = state.isTunnelOn)
        ActionButton(TnIcon.CHATS, "Message", actions::startChat, Modifier.weight(1f), isEnabled = state.isTunnelOn)
        ActionButton(TnIcon.CALL, "Call", actions::startAudioCall, Modifier.weight(1f), isEnabled = state.isTunnelOn)
        ActionButton(TnIcon.VIDEO, "Video", actions::startVideoCall, Modifier.weight(1f), isEnabled = state.isTunnelOn)
    }
}

/** The ten most recent calls; a Show more row opens the full list. */
@Composable
private fun CallsSection(state: ContactUiState, actions: ContactActions) {
    Section(title = "Calls") {
        if (state.callLogs.isEmpty()) row { Text("No calls yet.", style = TnType.body, color = TnTheme.colors.textMuted, modifier = Modifier.padding(TnSpace.lg)) }
        for (log in state.callLogs) row { CallLogRow(log) }
        if (state.hasMoreCalls) row { SettingRow(TnIcon.CHEVRON_RIGHT, "Show more", subtitle = "Every call with this contact", onClick = actions::openAllCalls) }
    }
}

/** Connection, security and traffic, folded behind a chevron. */
@Composable
private fun AdvancedSection(state: ContactUiState, actions: ContactActions) {
    if (!state.isTunnelOn) return
    val colors = TnTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TnSpace.lg)
            .padding(top = TnSpace.xl)
            .clip(RoundedCornerShape(TnRadius.lg))
            .background(colors.surface)
            .clickable(role = Role.Button, onClick = actions::toggleAdvanced)
            .defaultMinSize(minHeight = TnRow.height)
            .padding(horizontal = TnSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.sm),
    ) {
        Text("Advanced", style = TnType.body, color = colors.text)
        Text("Connection, security, traffic", style = TnType.small, color = colors.textMuted, modifier = Modifier.weight(1f))
        Icon(if (state.isAdvancedOpen) TnIcon.CHEVRON_DOWN else TnIcon.CHEVRON_RIGHT, tint = colors.textMuted, size = 20.dp)
    }
    if (!state.isAdvancedOpen) return
    val ping = pingColor(state.pingTint)
    Section(title = "Connection") {
        row { InfoField("Status", state.connectionStatus) }
        row { InfoField("Path and UDP endpoint", "${state.connectionPath} · ${state.endpoint}", isMono = true, isSmall = true) }
        row { InfoField("Latency (message link round trip)", state.latencyText, valueColor = ping) }
        row {
            SettingRow(
                TnIcon.PING,
                if (state.isPinging) "Pinging…" else "Ping",
                subtitle = if (state.queuedCount > 0) "Reaching them sends the ${state.queuedCount} queued action(s) now"
                else "Measures the round trip over the message link",
                onClick = if (state.isPinging) null else actions::ping,
                tone = when (state.pingTint) {
                    PingTint.ONLINE -> RowTone.SUCCESS
                    PingTint.REACHABLE -> RowTone.INFO
                    PingTint.OFFLINE -> RowTone.DANGER
                    PingTint.NONE -> RowTone.DEFAULT
                },
            )
        }
        row { SettingRow(TnIcon.SHIELD, "Reset tunnel", subtitle = "Drop the nebula tunnel to this peer and re-handshake", onClick = actions::resetTunnel) }
    }
    Section(title = "Security") {
        row { InfoField("Identity", "Authenticated by your nebula CA (Noise IK, mutual certificates)") }
        row { InfoField("Certificate name seen by nebula", state.peerCertName, isMono = true) }
        row { InfoField("Certificate fingerprint", state.peerCertFingerprint, isMono = true, isSmall = true) }
    }
    val s = state.stats
    Section(title = "Traffic") {
        row { InfoField("Messages", "${s.messagesSent} sent · ${s.messagesReceived} received") }
        row { InfoField("Media and files", "${s.mediaSent} sent · ${s.mediaReceived} received") }
        row { InfoField("Data over the message link", "↑ ${Format.bytes(s.bytesSent)}   ↓ ${Format.bytes(s.bytesReceived)}", isMono = true) }
        row { InfoField("First message → last activity", "${state.firstMessageText} → ${state.lastActivityText}") }
        row { InfoField("Peer's TeleNebula version", state.clientVersion, isMono = true) }
        if (state.queuedCount > 0 || s.failedActions > 0) {
            row { SettingRow(TnIcon.SEND, "Queued actions", subtitle = state.queueLabel, onClick = actions::sendQueuedNow) }
        }
    }
}

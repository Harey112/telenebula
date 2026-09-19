package com.telenebula.app.ui.screens.chatsettings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.Avatar
import com.telenebula.app.ui.fragments.LinkRow
import com.telenebula.app.ui.fragments.MediaTile
import com.telenebula.app.ui.fragments.OptionsMenu
import com.telenebula.app.ui.fragments.RowTone
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.ScreenHeader
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SelectMenuRow
import com.telenebula.app.ui.fragments.SelectRow
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnRow
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun ChatSettingsScreen(viewModel: ChatSettingsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TnTheme.colors
    Screen {
        ScreenHeader(title = "Chat settings", onBack = viewModel::goBack)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TnSpace.lg)
                .padding(top = TnSpace.sm)
                .clip(RoundedCornerShape(TnRadius.lg))
                .background(colors.surface)
                .clickable(role = Role.Button, onClick = viewModel::openContact)
                .semantics { contentDescription = "Open contact ${state.peerName}" }
                .padding(TnSpace.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
        ) {
            Avatar(state.peerName, size = TnRow.avatar)
            Column(modifier = Modifier.weight(1f)) {
                Text(state.peerName, style = TnType.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.ip, style = TnType.caption.copy(fontFamily = FontFamily.Monospace), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(TnIcon.CHEVRON_RIGHT, tint = colors.textMuted, size = 20.dp)
        }
        Section(title = "Chat") {
            row { SettingRow(TnIcon.BELL, "Notifications and sounds", subtitle = "Overrides for this chat", onClick = viewModel::openNotificationSettings) }
            row { SettingRow(TnIcon.CLOCK, "Disappearing messages", subtitle = state.disappearLabel, onClick = viewModel::openDisappearMenu) }
            row { SettingRow(if (state.isMuted) TnIcon.BELL else TnIcon.BELL_OFF, if (state.isMuted) "Unmute" else "Mute", subtitle = if (state.isMuted) "Alerts are off for this chat" else "Silence alerts for this chat", onClick = viewModel::toggleMute) }
            row { SettingRow(if (state.isArchived) TnIcon.UNARCHIVE else TnIcon.ARCHIVE, if (state.isArchived) "Unarchive" else "Archive", onClick = viewModel::toggleArchive) }
            row { SettingRow(TnIcon.SEARCH, "Search in chat", subtitle = "Find a message in this conversation", onClick = viewModel::openSearch) }
            row { SettingRow(TnIcon.FOLDER, "Export chat", subtitle = "Plain-text transcript", onClick = viewModel::exportChat) }
            row { SettingRow(TnIcon.SEND, "Queued actions", subtitle = state.queueLabel, onClick = viewModel::sendQueuedNow) }
        }
        Section(title = "Privacy") {
            row { SelectRow("Send read receipts", viewModel.privacyOptions, state.readReceipts.key, viewModel::setReadReceipts) }
            row { SelectRow("Send typing indicator", viewModel.privacyOptions, state.typingIndicators.key, viewModel::setTypingIndicators) }
            row { SelectRow("Block screenshots", viewModel.privacyOptions, state.blockScreenshots.key, viewModel::setBlockScreenshots) }
            row { SelectMenuRow(TnIcon.LOCK, "Reveal covered messages", state.revealGateLabel, viewModel::openRevealGateMenu) }
        }
        Section(title = "Media") {
            if (state.mediaPreview.isEmpty()) {
                row { Text("No photos, videos or files yet.", style = TnType.body, color = colors.textMuted, modifier = Modifier.padding(TnSpace.lg)) }
            } else {
                row {
                    Row(modifier = Modifier.fillMaxWidth().padding(start = TnSpace.lg, end = TnSpace.lg, top = TnSpace.lg, bottom = TnSpace.sm), horizontalArrangement = Arrangement.spacedBy(TnSpace.sm)) {
                        for ((index, msg) in state.mediaPreview.withIndex()) {
                            val isLast = index == state.mediaPreview.lastIndex
                            if (isLast && state.mediaMore > 0) {
                                MediaTile(msg, Modifier.weight(1f), onClick = viewModel::openMedia, moreCount = state.mediaMore)
                            } else {
                                MediaTile(msg, Modifier.weight(1f), onClick = { viewModel.openMediaItem(msg) })
                            }
                        }
                    }
                }
            }
            row { SettingRow(TnIcon.IMAGE, "All media", subtitle = state.mediaLabel, onClick = viewModel::openMedia) }
        }
        Section(title = "Links") {
            if (state.linksPreview.isEmpty()) row { Text("No links shared yet.", style = TnType.body, color = colors.textMuted, modifier = Modifier.padding(TnSpace.lg)) }
            for (link in state.linksPreview) row { LinkRow(link, onClick = { viewModel.openLink(link.url) }) }
            row { SettingRow(TnIcon.LINK, "All links", subtitle = state.linksLabel, onClick = viewModel::openLinks) }
        }
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
            row { SettingRow(TnIcon.CLEAR, "Clear history", subtitle = "Delete every message in this chat on this device", onClick = viewModel::clearHistory) }
            row { SettingRow(TnIcon.TRASH, "Delete chat", subtitle = "Removes the chat and the contact", onClick = viewModel::deleteChat, tone = RowTone.DANGER) }
        }
    }
    val menu = remember(state.disappearSeconds) { viewModel.disappearMenu(state.disappearSeconds) }
    OptionsMenu(isVisible = state.isDisappearMenuOpen, options = menu, onClose = viewModel::closeDisappearMenu, title = "Disappearing messages · ${state.disappearLabel}")
    val gateMenu = remember(state.revealGate) { viewModel.revealGateMenu(state.revealGate) }
    OptionsMenu(isVisible = state.isRevealGateMenuOpen, options = gateMenu, onClose = viewModel::closeRevealGateMenu, title = "Reveal covered messages · ${state.revealGateLabel}")
}

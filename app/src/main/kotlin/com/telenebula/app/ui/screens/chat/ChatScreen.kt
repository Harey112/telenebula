package com.telenebula.app.ui.screens.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.Pill
import com.telenebula.app.ui.fragments.Avatar
import com.telenebula.app.ui.fragments.ErrorBanner
import com.telenebula.app.ui.fragments.ForwardSheet
import com.telenebula.app.ui.fragments.MenuOption
import com.telenebula.app.ui.fragments.MessageActionsMenu
import com.telenebula.app.ui.fragments.MessageTimeline
import com.telenebula.app.ui.fragments.OptionsMenu
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnRow
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TnTheme.colors
    LifecycleResumeEffect(Unit) {
        viewModel.onShown()
        onPauseOrDispose { viewModel.onHidden() }
    }
    Column(modifier = Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        ChatHeader(state, viewModel)
        MessageList(state, viewModel, modifier = Modifier.weight(1f))
        ComposerBanner(state, viewModel)
        Composer(state, viewModel)
    }
    ChatDialogs(state, viewModel)
}

/** Peer identity with presence, or the inline search field; search, call and info actions. */
@Composable
private fun ChatHeader(state: ChatUiState, actions: ChatActions) {
    val colors = TnTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(TnRow.height).padding(horizontal = TnSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.sm),
    ) {
        Icon(TnIcon.BACK, tint = colors.text, contentDescription = "Back", modifier = Modifier.clickable(role = Role.Button, onClick = actions::goBack))
        if (state.isSearching) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            BasicTextField(
                value = state.searchQuery,
                onValueChange = actions::setSearchQuery,
                singleLine = true,
                textStyle = TnType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(TnRadius.md))
                    .background(colors.surfaceRaised)
                    .focusRequester(focus)
                    .semantics { contentDescription = "Search in chat" },
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.md), contentAlignment = Alignment.CenterStart) {
                        if (state.searchQuery.isEmpty()) Text("Search in chat", style = TnType.body, color = colors.textMuted)
                        inner()
                    }
                },
            )
            Icon(TnIcon.CLOSE, tint = colors.textMuted, size = 22.dp, contentDescription = "Close search", modifier = Modifier.clickable(role = Role.Button, onClick = actions::endSearch))
        } else {
            Row(
                modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = actions::openContact).semantics { contentDescription = "Open contact ${state.peerName}" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
            ) {
                Avatar(state.peerName, size = 40.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.peerName, style = TnType.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (state.isDisappearing) {
                            Box(
                                modifier = Modifier.size(18.dp).clip(CircleShape).background(colors.surfaceRaised).semantics { contentDescription = "Disappearing messages: ${state.disappearLabel}" },
                                contentAlignment = Alignment.Center,
                            ) { Icon(TnIcon.CLOCK, tint = colors.accent, size = 12.dp) }
                        }
                    }
                    Text(
                        state.peerSubtitle,
                        style = TnType.small,
                        color = when {
                            state.isPeerTyping -> colors.accent
                            state.isPeerOnline -> colors.success
                            !state.isPeerReachable -> colors.warning
                            else -> colors.textMuted
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!state.isArchived) {
                HeaderAction(TnIcon.CALL, "Voice call", enabled = state.isTunnelOn, onClick = actions::startAudioCall)
                PingAction(state, onClick = actions::pingPeer)
                HeaderAction(TnIcon.INFO, "Chat info and settings", onClick = actions::openChatSettings)
            }
        }
    }
}

/** The label carries all three states, so the colour is never the only signal. */
@Composable
private fun PingAction(state: ChatUiState, onClick: () -> Unit) {
    val colors = TnTheme.colors
    // built inside the branch so nothing animates at rest
    val alpha = if (state.isPinging) {
        rememberInfiniteTransition(label = "ping").animateFloat(
            initialValue = 1f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(tween(560, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pingAlpha",
        ).value
    } else {
        1f
    }
    val tint = when {
        state.isPinging -> colors.accent
        state.pingResult == PingResult.ONLINE -> colors.success
        state.pingResult == PingResult.REACHABLE -> colors.info
        state.pingResult == PingResult.OFFLINE -> colors.danger
        state.isTunnelOn -> colors.text
        else -> colors.hairline
    }
    val label = when {
        state.isPinging -> "Pinging ${state.peerName}"
        state.pingResult == PingResult.ONLINE -> "${state.peerName} is online"
        state.pingResult == PingResult.REACHABLE -> "${state.peerName} is reachable"
        state.pingResult == PingResult.OFFLINE -> "${state.peerName} is offline"
        else -> "Ping ${state.peerName}"
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = state.isTunnelOn && !state.isPinging, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(TnIcon.PING, tint = tint, size = 22.dp, contentDescription = label, modifier = Modifier.alpha(alpha))
    }
}

@Composable
private fun HeaderAction(icon: TnIcon, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = TnTheme.colors
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, tint = if (enabled) colors.text else colors.hairline, size = 22.dp, contentDescription = label) }
}

/** The inverted timeline of bubbles and date pills, with the error strip above it. */
@Composable
private fun MessageList(state: ChatUiState, actions: ChatActions, modifier: Modifier) {
    val colors = TnTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        state.error?.let { ErrorBanner(it, modifier = Modifier.padding(horizontal = TnSpace.sm), onDismiss = actions::clearError) }
        if (state.items.isEmpty()) {
            if (state.isLoaded) Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No messages here yet…", style = TnType.body, color = colors.textMuted) }
            return
        }
        Box(modifier = Modifier.fillMaxSize()) {
            MessageTimeline(
            items = state.items,
            actionsByMessage = state.actionsByMessage,
            replyPreviews = state.replyPreviews,
            linkRanges = state.linkRanges,
            transferProgress = state.transferProgress,
            textSizeSp = state.textSizeSp,
            isCompact = state.isCompact,
            expandedMessageId = state.expandedMessageId,
            seenAvatarMessageId = state.seenAvatarMessageId,
            peerName = state.peerName,
            isPeerReachable = state.isPeerReachable,
            onClick = actions::toggleMessageDetails,
            onLongClick = actions::openMessageMenu,
            onReply = actions::beginReply,
            onClickAttachment = actions::openAttachment,
            onCancelTransfer = actions::cancelIncomingTransfer,
            onClickReactions = actions::openReactions,
            onClickLink = actions::openLink,
            onRetrySend = actions::retrySendNow,
            onAcceptOffer = actions::acceptOffer,
            onDeclineOffer = actions::declineOffer,
            freeBytes = state.freeBytes,
            hasOlder = state.canLoadOlder,
            isLoadingOlder = state.isLoadingOlder,
            onReachOlder = actions::loadOlder,
            onReachNewest = actions::reachedNewest,
            voicePlayback = state.voicePlayback,
            onToggleVoice = actions::toggleVoice,
            )
            if (state.isDetachedFromLatest) {
                Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = TnSpace.lg)) {
                    Pill("Jump to latest", colors.accent, colors.onAccent, "Jump to the latest messages", TnIcon.CHEVRON_DOWN, actions::jumpToLatest)
                }
            }
        }
    }
}

/** The "replying to" or "editing" strip above the composer. */
@Composable
private fun ComposerBanner(state: ChatUiState, actions: ChatActions) {
    val colors = TnTheme.colors
    val (icon, text, onCancel, label) = when (val mode = state.composer) {
        is ComposerMode.Editing -> Banner(TnIcon.PENCIL, "Editing: ${mode.message.body}", actions::cancelEdit, "Cancel edit")
        is ComposerMode.Replying -> Banner(TnIcon.REPLY, "Replying to: ${mode.to.body.ifEmpty { mode.to.attachment?.name ?: "Attachment" }}", actions::cancelReply, "Cancel reply")
        ComposerMode.Idle -> return
    }
    HorizontalDivider(thickness = Dp.Hairline, color = colors.hairline)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg, vertical = TnSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.sm),
    ) {
        Icon(icon, tint = colors.accent, size = 16.dp)
        Text(text, style = TnType.small, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(TnIcon.CLOSE, tint = colors.textMuted, size = 18.dp, contentDescription = label, modifier = Modifier.clickable(role = Role.Button, onClick = onCancel))
    }
}

private data class Banner(val icon: TnIcon, val text: String, val onCancel: () -> Unit, val label: String)

/** Message input with attach and send; a notice instead when blocked or the tunnel is off. */
@Composable
private fun Composer(state: ChatUiState, actions: ChatActions) {
    val colors = TnTheme.colors
    val notice = when {
        state.isArchived -> ComposerNotice("This chat is archived.", "Unarchive", actions::unarchive)
        state.isBlocked -> ComposerNotice("You blocked this contact.", "Unblock", actions::unblock)
        !state.isTunnelOn -> ComposerNotice("Nebula tunnel is off.", "Turn it on", actions::openSettings)
        else -> null
    }
    if (notice != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = TnSpace.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TnSpace.md, Alignment.CenterHorizontally),
        ) {
            Text(notice.message, style = TnType.body, color = colors.textMuted)
            Text(
                notice.action,
                style = TnType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = colors.accent,
                modifier = Modifier.clickable(role = Role.Button, onClick = notice.onAction),
            )
        }
        return
    }
    state.voiceBar?.let { bar ->
        VoiceComposerBar(bar, onStop = actions::stopVoiceMessage, onCancel = actions::cancelVoiceMessage, onTogglePreview = actions::toggleVoicePreview, onSend = actions::sendVoiceMessage)
        return
    }
    Row(modifier = Modifier.fillMaxWidth().padding(TnSpace.sm), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(TnSpace.sm)) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(TnRadius.lg + 6.dp))
                .background(colors.surfaceRaised)
                .padding(horizontal = TnSpace.md, vertical = TnSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TnSpace.sm),
        ) {
            BasicTextField(
                value = state.draft,
                onValueChange = actions::setDraft,
                textStyle = TnType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = if (state.isEnterToSend) ImeAction.Send else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onSend = { if (state.canSend) actions.send() }),
                modifier = Modifier.weight(1f).heightIn(max = 110.dp).padding(vertical = TnSpace.sm).semantics { contentDescription = "Message" },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (state.draft.isEmpty()) Text("Message", style = TnType.body, color = colors.textMuted)
                        inner()
                    }
                },
            )
            Icon(TnIcon.SQUARE_PLUS, tint = colors.textMuted, size = 23.dp, contentDescription = "Add to message", modifier = Modifier.clickable(role = Role.Button, onClick = actions::openAttachSheet))
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .alpha(if (state.canSend) 1f else 0.5f)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable(enabled = state.canSend, role = Role.Button, onClick = actions::send)
                .semantics { contentDescription = if (state.composer is ComposerMode.Editing) "Save edit" else "Send message" },
            contentAlignment = Alignment.Center,
        ) { Icon(if (state.composer is ComposerMode.Editing) TnIcon.CHECK else TnIcon.SEND, tint = colors.onAccent, size = 20.dp) }
    }
}

private class ComposerNotice(val message: String, val action: String, val onAction: () -> Unit)

/** Takes the composer's place for a voice message: recording shows a timer and stop; stopped shows play, the length and cancel. Only Send sends. */
@Composable
private fun VoiceComposerBar(bar: VoiceBar, onStop: () -> Unit, onCancel: () -> Unit, onTogglePreview: () -> Unit, onSend: () -> Unit) {
    val colors = TnTheme.colors
    Row(modifier = Modifier.fillMaxWidth().padding(TnSpace.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TnSpace.sm)) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(TnRadius.lg + 6.dp))
                .background(colors.surfaceRaised)
                .padding(horizontal = TnSpace.md, vertical = TnSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
        ) {
            if (bar.isRecording) {
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(colors.danger))
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(colors.accentSoft)
                        .clickable(role = Role.Button, onClick = onTogglePreview)
                        .semantics { contentDescription = if (bar.isPreviewPlaying) "Pause the recording" else "Listen to the recording" },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (bar.isPreviewPlaying) TnIcon.PAUSE else TnIcon.PLAY, tint = colors.accent, size = 18.dp) }
            }
            Text(
                if (bar.isRecording) "Recording ${bar.label}" else bar.label,
                style = TnType.body,
                color = colors.text,
                modifier = Modifier.weight(1f).semantics { contentDescription = if (bar.isRecording) "Recording voice message, ${bar.label}" else "Recorded voice message, ${bar.label}" },
            )
            if (bar.isRecording) {
                Icon(TnIcon.STOP, tint = colors.danger, size = 20.dp, contentDescription = "Stop recording", modifier = Modifier.size(36.dp).clickable(role = Role.Button, onClick = onStop).padding(8.dp))
            } else {
                Icon(TnIcon.CLOSE, tint = colors.textMuted, size = 22.dp, contentDescription = "Discard the recording", modifier = Modifier.size(36.dp).clickable(role = Role.Button, onClick = onCancel).padding(7.dp))
            }
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable(role = Role.Button, onClick = onSend)
                .semantics { contentDescription = "Send voice message" },
            contentAlignment = Alignment.Center,
        ) { Icon(TnIcon.SEND, tint = colors.onAccent, size = 20.dp) }
    }
}

/** Long-press message menu, attachment picker, media viewer and forward sheet. */
@Composable
private fun ChatDialogs(state: ChatUiState, actions: ChatActions) {
    val overlay = state.overlay
    MessageActionsMenu(
        state = (overlay as? ChatOverlay.Menu)?.menu,
        quickReactions = state.quickReactions,
        onClose = actions::closeMessageMenu,
        onReact = actions::reactWith,
        onMoreReactions = actions::openReactionPicker,
        onReply = actions::replyFromMenu,
        onEdit = actions::beginEdit,
        onCopy = actions::copyMessage,
        onForward = actions::openForward,
        onShowActions = actions::openActionsSheet,
        onDeleteForMe = actions::deleteForMe,
        onDeleteForEveryone = actions::deleteForEveryone,
    )
    OptionsMenu(
        isVisible = overlay is ChatOverlay.Attach,
        title = "Attach",
        options = remember(actions) {
            listOf(
                MenuOption("media", TnIcon.IMAGE, "Photos & Videos", onClick = actions::attachMedia),
                MenuOption("file", TnIcon.FOLDER, "Files & Documents", onClick = actions::attachFile),
                MenuOption("voice", TnIcon.MIC, "Voice message", onClick = actions::startVoiceMessage),
                MenuOption("cover", TnIcon.EYE, "Cover message · coming soon", onClick = actions::coverMessageSoon),
            )
        },
        onClose = actions::closeAttachSheet,
    )
    ForwardSheet(isVisible = overlay is ChatOverlay.Forward, contacts = (overlay as? ChatOverlay.Forward)?.contacts.orEmpty(), onPick = actions::forwardTo, onClose = actions::closeForward)
}

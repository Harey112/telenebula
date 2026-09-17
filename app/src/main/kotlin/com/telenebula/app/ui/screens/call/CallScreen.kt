package com.telenebula.app.ui.screens.call

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.CallAvatar
import com.telenebula.app.ui.fragments.CallPalette
import com.telenebula.app.ui.fragments.OverlayPermissionSheet
import com.telenebula.app.ui.fragments.RemoteVideoView
import com.telenebula.app.ui.fragments.RoundControl
import com.telenebula.app.ui.fragments.SettleOnBounds
import com.telenebula.app.ui.fragments.VideoTile
import com.telenebula.app.ui.fragments.floatingDrag
import com.telenebula.app.ui.fragments.rememberFloatingBoxState
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.calls.CallPhase
import kotlin.math.roundToInt

private val PIP_WIDTH = 104.dp
private val PIP_HEIGHT = 158.dp
private val PIP_MARGIN = 14.dp

@Composable
fun CallScreen(viewModel: CallViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    KeepScreenOn()
    if (state.isRemoteVideoLive) VideoCallStage(state, viewModel) else VoiceCallStage(state, viewModel)
    OverlayPermissionSheet(isVisible = state.isOverlayPromptOpen, onAllow = viewModel::allowOverlay, onDecline = viewModel::declineOverlay)
}

@Composable
private fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** Every call action in one row. Ringing in: Decline · Accept. Failed: Retry · Close. Otherwise the live controls. */
@Composable
private fun CallControls(state: CallUiState, actions: CallActions) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.Top) {
        when {
            state.phase == CallPhase.INCOMING -> {
                RoundControl(TnIcon.CALL_END, "Decline", actions::decline, Modifier.weight(1f), color = CallPalette.red)
                RoundControl(TnIcon.CALL, "Accept", actions::accept, Modifier.weight(1f), color = CallPalette.green)
            }
            state.hasFailed -> {
                RoundControl(TnIcon.RETRY, "Retry", actions::retry, Modifier.weight(1f), color = CallPalette.green)
                RoundControl(TnIcon.CLOSE, "Close", actions::exit, Modifier.weight(1f), color = CallPalette.neutral)
            }
            state.phase == null -> Unit
            else -> {
                RoundControl(TnIcon.SPEAKER, "Speaker", actions::toggleSpeaker, Modifier.weight(1f), isActive = state.speaker)
                RoundControl(if (state.camOff) TnIcon.VIDEO_OFF else TnIcon.VIDEO, "Camera", actions::toggleCamera, Modifier.weight(1f), isActive = state.camOff)
                if (!state.camOff) RoundControl(TnIcon.FLIP_CAMERA, "Flip", actions::switchCamera, Modifier.weight(1f))
                RoundControl(if (state.muted) TnIcon.MIC_OFF else TnIcon.MIC, "Mute", actions::toggleMute, Modifier.weight(1f), isActive = state.muted)
                RoundControl(TnIcon.CALL_END, "End", actions::end, Modifier.weight(1f), color = CallPalette.red)
            }
        }
    }
}

@Composable
private fun MinimizeButton(state: CallUiState, actions: CallActions, modifier: Modifier) {
    if (!state.canMinimize) return
    Box(
        modifier = modifier.size(44.dp).clickable(role = Role.Button, onClick = actions::minimize),
        contentAlignment = Alignment.Center,
    ) { Icon(TnIcon.MINIMIZE, tint = CallPalette.inkDim, size = 26.dp, contentDescription = "Minimize call") }
}

/** Draggable local camera tile, snapping to the nearest corner. */
@Composable
private fun SelfView(state: CallUiState, actions: CallActions) {
    val track = state.localVideo
    if (!state.showLocalVideo || track == null) return
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bounds = with(density) {
        Rect(
            left = PIP_MARGIN.toPx(),
            top = (statusBar + PIP_MARGIN).toPx(),
            right = (configuration.screenWidthDp.dp - PIP_WIDTH - PIP_MARGIN).toPx(),
            bottom = (configuration.screenHeightDp.dp - PIP_HEIGHT - navBar - 150.dp).toPx(),
        )
    }
    val pip = rememberFloatingBoxState(Offset(bounds.right, bounds.top)).also { it.snapToCorners = true }
    pip.SettleOnBounds(bounds)
    Box(
        modifier = Modifier
            .offset { IntOffset(pip.offset.value.x.roundToInt(), pip.offset.value.y.roundToInt()) }
            .width(PIP_WIDTH)
            .height(PIP_HEIGHT)
            .floatingDrag(pip),
    ) { VideoTile(track, actions.egl, modifier = Modifier.fillMaxSize(), mirror = state.isFrontCamera, cornerRadius = 16.dp) }
}

/** Full-screen remote video with tap-to-toggle controls. */
@Composable
private fun VideoCallStage(state: CallUiState, actions: CallActions) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = actions::toggleControls),
    ) {
        RemoteVideoView(state.remoteVideo, actions.egl, modifier = Modifier.fillMaxSize())
        SelfView(state, actions)
        if (state.areControlsShown) {
            Column(
                modifier = Modifier.fillMaxWidth().background(CallPalette.scrim).padding(top = statusBar + 14.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(state.peerName, style = TextStyle(color = CallPalette.ink, fontSize = 22.sp))
                Text(state.statusLabel, style = TextStyle(color = CallPalette.inkMuted, fontSize = 13.5.sp))
            }
            MinimizeButton(state, actions, Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = statusBar + 10.dp))
            Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(CallPalette.scrim).padding(top = 22.dp, bottom = navBar + 24.dp)) {
                CallControls(state, actions)
            }
        }
    }
}

/** Identity block above one row of controls for ringing, connecting, audio and failed calls. */
@Composable
private fun VoiceCallStage(state: CallUiState, actions: CallActions) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val isLabelAbove = state.phase == CallPhase.INCOMING || state.phase == CallPhase.CONTACTING || state.phase == CallPhase.RINGING || state.phase == CallPhase.CONNECTING
    Box(modifier = Modifier.fillMaxSize().background(CallPalette.ground)) {
        val local = state.localVideo
        if (state.showLocalVideo && local != null) {
            RemoteVideoView(local, actions.egl, modifier = Modifier.fillMaxSize(), mirror = state.isFrontCamera)
            Box(modifier = Modifier.fillMaxSize().background(CallPalette.dim))
        }
        MinimizeButton(state, actions, Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = statusBar + 10.dp))
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = statusBar + 64.dp, start = 32.dp, end = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (isLabelAbove) Text(state.statusLabel, style = TextStyle(color = CallPalette.inkMuted, fontSize = 15.5.sp))
                CallAvatar(state.peerName, size = 110.dp)
                Text(state.peerName, style = TextStyle(color = CallPalette.ink, fontSize = 34.sp, letterSpacing = 0.2.sp), textAlign = TextAlign.Center, maxLines = 2)
                if (!isLabelAbove) Text(state.statusLabel, style = TextStyle(color = CallPalette.inkMuted, fontSize = 15.5.sp, lineHeight = 22.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(modifier = Modifier.padding(bottom = navBar + 40.dp)) { CallControls(state, actions) }
        }
    }
}

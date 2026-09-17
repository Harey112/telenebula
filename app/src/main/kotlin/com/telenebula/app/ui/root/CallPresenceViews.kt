package com.telenebula.app.ui.root

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.runtime.PresenceState
import com.telenebula.app.ui.fragments.CallPalette
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon

/**
 * In-flow ongoing-AUDIO-call banner above the navigator. It participates in layout so header
 * buttons are never covered; the shell consumes the status-bar inset underneath it so screens
 * do not add a second gap.
 */
@Composable
fun CallBanner(presenceFlow: StateFlow<PresenceState>, onOpen: () -> Unit) {
    val presence by presenceFlow.collectAsStateWithLifecycle()
    if (!presence.isOffCallScreen || presence.hasVideo) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CallPalette.bannerGreen)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "Return to call with ${presence.peerName}" }
            .statusBarsPadding()
            .padding(top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        Icon(TnIcon.CALL, tint = CallPalette.ink, size = 14.dp)
        Text("${presence.peerName} · ${presence.statusLabel}", style = TextStyle(color = CallPalette.ink, fontSize = 13.5.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

package com.telenebula.app.ui.fragments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

enum class PeerPath(val label: String) { DIRECT("Direct"), RELAYED("Relayed"), HANDSHAKING("Handshaking") }

/** One established nebula tunnel: who, how (direct/relayed) and where. */
class PeerRow(
    val ip: String,
    val label: String,
    val endpoint: String,
    val path: PeerPath,
    val isLighthouse: Boolean,
    val isContact: Boolean,
)

@Composable
fun PeerRowItem(peer: PeerRow, onOpen: (String) -> Unit) {
    val colors = TnTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (peer.isContact) Modifier.clickable(role = Role.Button) { onOpen(peer.ip) } else Modifier)
            .semantics { contentDescription = "${peer.label}, ${peer.path.label}, ${peer.endpoint}" }
            .padding(horizontal = TnSpace.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
    ) {
        Icon(
            if (peer.isLighthouse) TnIcon.SHIELD else TnIcon.PERSON,
            tint = if (peer.path == PeerPath.DIRECT) colors.success else colors.textMuted,
            size = 18.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (peer.isLighthouse) "${peer.label}  · lighthouse" else peer.label,
                style = TnType.body.copy(fontWeight = FontWeight.Medium),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${peer.ip} · ${peer.path.label} · ${peer.endpoint}",
                style = TnType.caption.copy(fontFamily = FontFamily.Monospace),
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

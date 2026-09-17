package com.telenebula.app.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.InfoField
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SettingRow
import com.telenebula.app.ui.fragments.Spinner
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel) {
    val s by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TnTheme.colors
    Screen(title = "Diagnostics", onBack = viewModel::goBack) {
        Section(title = "Connectivity") {
            row { InfoField("Lighthouse", s.lighthouseStatus) }
            row {
                SettingRow(
                    TnIcon.RETRY,
                    if (s.isTesting) "Testing…" else "Test peer connectivity",
                    subtitle = "Pings every contact over the message link and shows the round trip",
                    onClick = if (s.isTesting) null else viewModel::runPeerTest,
                )
            }
            for (r in s.results) {
                row { key(r.ip) { PingResultRow(r) } }
            }
        }
        Section(title = "Delivery") {
            row {
                SettingRow(
                    TnIcon.SEND,
                    "Send queued actions now",
                    subtitle = when {
                        s.queuedCount == 0 && s.retryFailedCount == 0 -> "Nothing waiting"
                        s.queuedCount == 0 -> "${s.retryFailedCount} refused — tap to try again"
                        else -> "${s.queuedCount} waiting across ${s.queuedPeers} peer(s)"
                    },
                    onClick = viewModel::sendAllQueuedNow,
                )
            }
        }
        Section(title = "Report") {
            row {
                SettingRow(
                    TnIcon.FOLDER,
                    if (s.isExporting) "Preparing…" else "Export diagnostics",
                    subtitle = "Versions, node, hostmap, stats and the nebula log tail as JSON",
                    onClick = if (s.isExporting) null else viewModel::exportDiagnostics,
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(start = TnSpace.lg, end = TnSpace.lg, top = TnSpace.xl)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = TnSpace.sm, start = TnSpace.xs, end = TnSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("LAST CALL", style = TnType.small.copy(letterSpacing = 0.6.sp), color = colors.textMuted, modifier = Modifier.weight(1f))
            }
            LogBox(if (s.lastCallTrail.isEmpty()) "No call yet." else s.lastCallTrail.joinToString("\n"))
        }
        Column(modifier = Modifier.fillMaxWidth().padding(start = TnSpace.lg, end = TnSpace.lg, top = TnSpace.xl)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = TnSpace.sm, start = TnSpace.xs, end = TnSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TnSpace.lg),
            ) {
                Text("NEBULA LOG", style = TnType.small.copy(letterSpacing = 0.6.sp), color = colors.textMuted, modifier = Modifier.weight(1f))
                LinkText("Refresh", colors.accent, viewModel::refreshLog)
                LinkText("Clear", colors.danger, viewModel::clearLog)
            }
            LogBox(s.logTail.ifEmpty { "Log is empty." })
        }
    }
}

/** A scrollable, selectable monospace block on the surface tone. */
@Composable
private fun LogBox(text: String) {
    val colors = TnTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(TnRadius.lg))
            .background(colors.surface)
            .verticalScroll(rememberScrollState())
            .padding(TnSpace.md),
    ) {
        SelectionContainer {
            Text(text, style = TnType.caption.copy(fontFamily = FontFamily.Monospace, lineHeight = 15.sp), color = colors.textMuted)
        }
    }
}

@Composable
private fun LinkText(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label,
        style = TnType.small.copy(fontWeight = FontWeight.Medium),
        color = color,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "$label log" }.padding(TnSpace.xs),
    )
}

@Composable
private fun PingResultRow(r: PingResult) {
    val colors = TnTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg, vertical = TnSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.sm),
    ) {
        Text(r.label, style = TnType.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val rtt = r.rttMs
        when {
            r.isRunning -> Spinner(size = 14.dp)
            rtt == null -> Unit
            rtt < 0 -> Text("no answer", style = TnType.small.copy(fontFamily = FontFamily.Monospace), color = colors.danger)
            else -> Text("$rtt ms", style = TnType.small.copy(fontFamily = FontFamily.Monospace), color = colors.success)
        }
    }
}

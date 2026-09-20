package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/**
 * Material3's own clock, the one place the app uses its chrome: a dial nobody would hand-build,
 * drawn inside the app's in-tree overlay so notices still sit above it. Hours are 24-hour, as
 * every time this app shows is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerModal(
    isVisible: Boolean,
    title: String,
    hour: Int,
    minute: Int,
    onCancel: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    if (!isVisible) return
    val colors = TnTheme.colors
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    CenteredOverlay(isVisible = true, onDismiss = onCancel, horizontalPadding = 24.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .padding(TnSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                style = TnType.body.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium),
                color = colors.text,
                modifier = Modifier.fillMaxWidth().padding(bottom = TnSpace.md),
            )
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = colors.surfaceRaised,
                    clockDialSelectedContentColor = colors.onAccent,
                    clockDialUnselectedContentColor = colors.text,
                    selectorColor = colors.accent,
                    containerColor = colors.surface,
                    periodSelectorBorderColor = colors.hairline,
                    timeSelectorSelectedContainerColor = colors.accentSoft,
                    timeSelectorUnselectedContainerColor = colors.surfaceRaised,
                    timeSelectorSelectedContentColor = colors.accent,
                    timeSelectorUnselectedContentColor = colors.text,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = TnSpace.md),
                horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.End),
            ) {
                Text(
                    "Cancel",
                    style = TnType.body.copy(fontWeight = FontWeight.Medium),
                    color = colors.textMuted,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onCancel),
                )
                Text(
                    "Set",
                    style = TnType.body.copy(fontWeight = FontWeight.Medium),
                    color = colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onConfirm(state.hour, state.minute) },
                )
            }
        }
    }
}

/** 24-hour, zero padded: the one way this app writes a time. */
fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

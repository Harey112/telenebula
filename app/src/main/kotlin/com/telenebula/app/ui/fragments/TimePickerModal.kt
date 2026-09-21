package com.telenebula.app.ui.fragments

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme

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
    TnModal(
        isVisible = true,
        title = title,
        onDismiss = onCancel,
        confirmLabel = "Set",
        onConfirm = { onConfirm(state.hour, state.minute) },
        horizontalPadding = 24.dp,
        isCentred = true,
    ) {
        TimePicker(
            state = state,
            modifier = Modifier.padding(top = TnSpace.md),
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
    }
}

/** 24-hour, zero padded: the one way this app writes a time. */
fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

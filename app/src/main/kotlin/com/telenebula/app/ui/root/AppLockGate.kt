package com.telenebula.app.ui.root

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.telenebula.app.ui.theme.TnTheme

/**
 * While the app lock is armed the system prompt is shown directly; this is only an opaque cover so
 * the content underneath stays hidden. Tapping it re-opens the prompt after a cancel.
 */
@Composable
fun AppLockGate(isLocked: Boolean, onRetry: () -> Unit) {
    if (!isLocked) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TnTheme.colors.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onRetry)
            .semantics {
                contentDescription = "Unlock TeleNebula"
                role = Role.Button
            },
    )
}

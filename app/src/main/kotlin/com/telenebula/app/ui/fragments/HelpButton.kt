package com.telenebula.app.ui.fragments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/** Opens an explanation by topic; the root provides it, so a fragment never reaches for the notice store. */
val LocalHelp = staticCompositionLocalOf<(topic: String, text: String) -> Unit> { { _, _ -> } }

/** The "?" beside a label: what it explains is read in a modal instead of under the label. */
@Composable
fun HelpButton(topic: String, text: String, modifier: Modifier = Modifier) {
    val show = LocalHelp.current
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = { show(topic, text) })
            .semantics { contentDescription = "About $topic" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(TnIcon.HELP, tint = TnTheme.colors.textMuted, size = 14.dp)
    }
}

/** A section's small caps title with its "?", for a group of rows or a screen part that needs explaining. */
@Composable
fun HelpHeading(title: String, help: String?, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title.uppercase(),
            style = TnType.small.copy(letterSpacing = 0.6.sp),
            color = TnTheme.colors.textMuted,
            modifier = Modifier.padding(horizontal = TnSpace.xs),
        )
        if (help != null) HelpButton(title, help)
    }
}

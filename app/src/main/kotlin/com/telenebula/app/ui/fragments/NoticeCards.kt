package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.telenebula.app.notices.Prompt
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
private fun NoticeCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TnRadius.lg))
            .background(TnTheme.colors.surface)
            .padding(TnSpace.lg),
    ) { content() }
}

@Composable
private fun NoticeHead(icon: TnIcon, color: Color, title: String) {
    val colors = TnTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = TnSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(lerp(color, colors.surface, 0.82f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, tint = color, size = 18.dp) }
        Text(title, style = TnType.title, color = colors.text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NoticeButton(label: String, color: Color, textColor: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(TnRadius.md))
            .background(color)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = TnType.body.copy(fontWeight = FontWeight.Medium), color = textColor) }
}

/** One error reads as a message; several become a scrollable bulleted list. */
@Composable
fun ErrorsCard(errors: List<String>, onDismiss: () -> Unit) {
    val colors = TnTheme.colors
    NoticeCard {
        NoticeHead(TnIcon.CLOSE, colors.danger, if (errors.size == 1) "Error" else "Errors (${errors.size})")
        if (errors.size == 1) {
            Text(errors[0], style = TnType.body, color = colors.textMuted, modifier = Modifier.padding(bottom = TnSpace.lg))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(bottom = TnSpace.lg)) {
                items(errors, key = { it }) { Text("• $it", style = TnType.body, color = colors.textMuted, modifier = Modifier.padding(bottom = TnSpace.xs)) }
            }
        }
        NoticeButton("OK", colors.danger, Color.White, onDismiss, Modifier.fillMaxWidth())
    }
}

/** One warning at a time; the button says how many wait behind it. */
@Composable
fun WarningCard(message: String, remaining: Int, onDismiss: () -> Unit) {
    val colors = TnTheme.colors
    NoticeCard {
        NoticeHead(TnIcon.SHIELD, colors.warning, "Warning")
        Text(message, style = TnType.body, color = colors.textMuted, modifier = Modifier.padding(bottom = TnSpace.lg))
        NoticeButton(if (remaining > 1) "OK (${remaining - 1} more)" else "OK", colors.warning, Color.White, onDismiss, Modifier.fillMaxWidth())
    }
}

@Composable
fun SuccessCard(message: String, onDismiss: () -> Unit) {
    val colors = TnTheme.colors
    NoticeCard {
        NoticeHead(TnIcon.CHECK, colors.success, "Success")
        Text(message, style = TnType.body, color = colors.textMuted, modifier = Modifier.padding(bottom = TnSpace.lg))
        NoticeButton("OK", colors.success, Color.White, onDismiss, Modifier.fillMaxWidth())
    }
}

/** A question with two buttons and, when a placeholder is given, a text field. */
@Composable
fun PromptCard(prompt: Prompt, value: String, onValueChange: (String) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit, autoFocus: Boolean) {
    val colors = TnTheme.colors
    val hasInput = prompt.placeholder != null
    val confirmColor = if (prompt.isDestructive) colors.danger else colors.accent
    val focus = remember { FocusRequester() }
    if (hasInput && autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    NoticeCard {
        NoticeHead(TnIcon.INFO, colors.accent, if (hasInput) "Input required" else "Please confirm")
        Text(prompt.message, style = TnType.body, color = colors.textMuted, modifier = Modifier.padding(bottom = TnSpace.lg))
        if (hasInput) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TnType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(TnRadius.md))
                    .background(colors.surfaceRaised)
                    .focusRequester(focus)
                    .padding(bottom = 0.dp),
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(prompt.placeholder.orEmpty(), style = TnType.body, color = colors.textMuted)
                        inner()
                    }
                },
            )
            Box(Modifier.height(TnSpace.lg))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TnSpace.sm)) {
            NoticeButton(prompt.leftLabel ?: "Cancel", colors.surfaceRaised, colors.textMuted, onCancel, Modifier.weight(1f))
            NoticeButton(prompt.rightLabel ?: "Confirm", confirmColor, if (prompt.isDestructive) Color.White else colors.onAccent, onConfirm, Modifier.weight(1f))
        }
    }
}

/** Non-dismissable progress card; the task that set it clears it. */
@Composable
fun LoadingCard(message: String) {
    val colors = TnTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TnRadius.lg))
            .background(colors.surface)
            .padding(TnSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.accent)
        Text(message, style = TnType.body, color = colors.text, modifier = Modifier.weight(1f))
    }
}

package com.telenebula.app.ui.fragments

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/** Full-window dim that closes on tap and on back; drawn in-tree so notices can still cover it. */
@Composable
fun Scrim(onDismiss: () -> Unit, label: String = "Close") {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TnTheme.colors.overlay)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .semantics { contentDescription = label },
    )
}

/**
 * A centred card over a scrim: options menus, small edit dialogs. Content taps do not close it.
 * Replaces the RN `Modal`-based overlays so it sits below the root notices, not above them.
 */
@Composable
fun CenteredOverlay(isVisible: Boolean, onDismiss: () -> Unit, horizontalPadding: androidx.compose.ui.unit.Dp = 42.dp, content: @Composable BoxScope.() -> Unit) {
    AnimatedVisibility(visible = isVisible, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
        Box(modifier = Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            Scrim(onDismiss)
            Box(
                modifier = Modifier
                    .padding(horizontal = horizontalPadding)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                content = content,
            )
        }
    }
}

/** One entry of an [OptionsMenu]. */
data class MenuOption(val key: String, val icon: TnIcon, val label: String, val isDanger: Boolean = false, val onClick: () -> Unit)

/** Generic centred options menu (e.g. chat or contact long-press). */
@Composable
fun OptionsMenu(isVisible: Boolean, options: List<MenuOption>, onClose: () -> Unit, title: String? = null) {
    val colors = TnTheme.colors
    CenteredOverlay(isVisible = isVisible, onDismiss = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .padding(vertical = 6.dp),
        ) {
            if (title != null) {
                Text(title, style = TnType.small.copy(fontSize = 13.5.sp), color = colors.textMuted, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 4.dp))
            }
            for (option in options) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = option.onClick)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(option.icon, tint = if (option.isDanger) colors.danger else colors.text, size = 19.dp)
                    Text(option.label, style = TnType.body.copy(fontSize = 15.5.sp), color = if (option.isDanger) colors.danger else colors.text)
                }
            }
        }
    }
}

/** A one-line text edit with Cancel/Save. */
@Composable
fun EditNameModal(
    isVisible: Boolean,
    value: String,
    onChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    title: String = "Edit name",
    placeholder: String = "Display name",
) {
    val colors = TnTheme.colors
    val focus = remember { FocusRequester() }
    CenteredOverlay(isVisible = isVisible, onDismiss = onCancel, horizontalPadding = 32.dp) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .padding(20.dp),
        ) {
            Text(title, style = TnType.body.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium), color = colors.text)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TnType.body.copy(fontSize = 16.sp, color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceRaised)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (value.isEmpty()) Text(placeholder, style = TnType.body.copy(fontSize = 16.sp), color = colors.textMuted)
                        inner()
                    }
                },
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.End)) {
                Text("Cancel", style = TnType.body.copy(fontWeight = FontWeight.Medium), color = colors.textMuted, modifier = Modifier.clickable(role = Role.Button, onClick = onCancel))
                Text("Save", style = TnType.body.copy(fontWeight = FontWeight.Medium), color = colors.accent, modifier = Modifier.clickable(role = Role.Button, onClick = onSave).semantics { contentDescription = "Save name" })
            }
        }
    }
}

/**
 * Bottom sheet drawn inside the app's own tree (no window), so it sits on the physical bottom
 * edge, pads its content by the navigation inset and stays below the root notices.
 */
@Composable
fun TnBottomSheet(isVisible: Boolean, title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = TnTheme.colors
    AnimatedVisibility(visible = isVisible, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Scrim(onClose, label = "Close sheet")
            AnimatedVisibility(visible = true, enter = slideInVertically(tween(180)) { it }, exit = slideOutVertically(tween(180)) { it }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .background(colors.surface)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(start = TnSpace.lg, end = TnSpace.lg, top = TnSpace.sm, bottom = TnSpace.lg),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.hairline)
                            .padding(bottom = 0.dp),
                    )
                    Text(title, style = TnType.title, color = colors.text, modifier = Modifier.padding(top = TnSpace.md, bottom = TnSpace.sm))
                    content()
                }
            }
        }
    }
}

package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/**
 * The one centred dialog: a title, whatever the caller puts under it, and a row of text actions.
 * Every dialog in the app is this with different content; none draws its own card.
 */
@Composable
fun TnModal(
    isVisible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = "Cancel",
    confirmLabel: String? = null,
    onConfirm: () -> Unit = {},
    isConfirmEnabled: Boolean = true,
    /** what a screen reader calls the confirm action; the label alone can be as vague as "Save" */
    confirmDescription: String? = null,
    horizontalPadding: Dp = 32.dp,
    isCentred: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TnTheme.colors
    CenteredOverlay(isVisible = isVisible, onDismiss = onDismiss, horizontalPadding = horizontalPadding) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TnRadius.lg))
                .background(colors.surface)
                .padding(20.dp),
            horizontalAlignment = if (isCentred) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Text(title, style = TnType.body.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium), color = colors.text)
            content()
            if (dismissLabel != null || confirmLabel != null) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.End)) {
                    if (dismissLabel != null) {
                        Text(
                            dismissLabel,
                            style = TnType.body.copy(fontWeight = FontWeight.Medium),
                            color = colors.textMuted,
                            modifier = Modifier.clickable(role = Role.Button, onClick = onDismiss),
                        )
                    }
                    if (confirmLabel != null) {
                        Text(
                            confirmLabel,
                            style = TnType.body.copy(fontWeight = FontWeight.Medium),
                            color = if (isConfirmEnabled) colors.accent else colors.hairline,
                            modifier = Modifier
                                .clickable(enabled = isConfirmEnabled, role = Role.Button, onClick = onConfirm)
                                .semantics { contentDescription = confirmDescription ?: confirmLabel },
                        )
                    }
                }
            }
        }
    }
}

/** The one centred menu: a card of rows, with an optional muted title above them. */
@Composable
fun TnMenu(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TnTheme.colors
    CenteredOverlay(isVisible = isVisible, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TnRadius.lg))
                .background(colors.surface)
                .padding(vertical = 6.dp),
        ) {
            if (title != null) {
                Text(title, style = TnType.small.copy(fontSize = 13.5.sp), color = colors.textMuted, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 4.dp))
            }
            content()
        }
    }
}

/** A text field as a dialog draws it: raised, rounded, placeholder in the muted ink. */
@Composable
fun ModalTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isMono: Boolean = false,
    isMultiline: Boolean = false,
    focus: FocusRequester? = null,
) {
    val colors = TnTheme.colors
    val style = if (isMono) TnType.body.copy(fontSize = 16.sp, color = colors.text, fontFamily = FontFamily.Monospace) else TnType.body.copy(fontSize = 16.sp, color = colors.text)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = !isMultiline,
        textStyle = style,
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = if (isMono) KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false) else KeyboardOptions.Default,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceRaised)
            .then(if (isMultiline) Modifier.defaultMinSize(minHeight = 72.dp) else Modifier)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = if (isMultiline) Alignment.TopStart else Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = style, color = colors.textMuted)
                inner()
            }
        },
    )
}

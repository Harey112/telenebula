package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun TnTextField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    /** small explanation under the field */
    helper: String? = null,
    /** validation message; colours the field border */
    error: String? = null,
    isMono: Boolean = false,
    isMultiline: Boolean = false,
    isNumeric: Boolean = false,
    hasAutoCapitalize: Boolean = true,
) {
    val colors = TnTheme.colors
    val textStyle = if (isMono) TnType.body.copy(color = colors.text, fontFamily = FontFamily.Monospace) else TnType.body.copy(color = colors.text)
    Column(modifier = modifier.fillMaxWidth().padding(top = TnSpace.md)) {
        if (label != null) {
            Text(label, style = TnType.small, color = colors.textMuted, modifier = Modifier.padding(bottom = TnSpace.xs, start = TnSpace.xs, end = TnSpace.xs))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.accent),
            singleLine = !isMultiline,
            keyboardOptions = KeyboardOptions(
                capitalization = if (hasAutoCapitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                autoCorrectEnabled = hasAutoCapitalize,
                keyboardType = if (isNumeric) KeyboardType.Number else KeyboardType.Text,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isMultiline) Modifier.defaultMinSize(minHeight = 88.dp) else Modifier.height(48.dp))
                .clip(RoundedCornerShape(TnRadius.md))
                .background(colors.surfaceRaised)
                .then(if (error != null) Modifier.border(1.dp, colors.danger, RoundedCornerShape(TnRadius.md)) else Modifier),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TnSpace.lg, vertical = if (isMultiline) TnSpace.md else 0.dp),
                    contentAlignment = if (isMultiline) Alignment.TopStart else Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = textStyle, color = colors.textMuted)
                    inner()
                }
            },
        )
        val note = error ?: helper
        if (note != null) {
            Text(
                note,
                style = TnType.caption,
                color = if (error != null) colors.danger else colors.textMuted,
                modifier = Modifier.padding(top = TnSpace.xs, start = TnSpace.xs, end = TnSpace.xs),
            )
        }
    }
}

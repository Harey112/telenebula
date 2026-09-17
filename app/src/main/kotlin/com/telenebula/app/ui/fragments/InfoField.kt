package com.telenebula.app.ui.fragments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun InfoField(
    label: String,
    value: String,
    isMono: Boolean = false,
    /** long values (fingerprints, network lists): smaller font, full wrap */
    isSmall: Boolean = false,
    /** status colouring (certificate validity, connection state) */
    valueColor: Color? = null,
) {
    val colors = TnTheme.colors
    var style = if (isSmall) TnType.caption.copy(lineHeight = 17.sp) else TnType.body
    if (isMono) style = style.copy(fontFamily = FontFamily.Monospace)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg, vertical = TnSpace.md),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = TnType.caption, color = colors.textMuted)
        SelectionContainer { Text(value, style = style, color = valueColor ?: colors.text) }
    }
}

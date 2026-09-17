package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/** Edits a contact's overlay address, nickname and notes (the username belongs to the peer). */
@Composable
fun ContactEditModal(
    isVisible: Boolean,
    address: String,
    onAddressChange: (String) -> Unit,
    nickname: String,
    onNicknameChange: (String) -> Unit,
    notes: String,
    onNotesChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = TnTheme.colors
    CenteredOverlay(isVisible = isVisible, onDismiss = onCancel, horizontalPadding = 32.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .padding(20.dp),
        ) {
            Text("Edit contact", style = TnType.body.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium), color = colors.text)
            ModalInput(address, onAddressChange, "Nebula IPv6 address", isMono = true)
            Text(
                "Changing the address moves this chat with it. Use it when the contact received a new certificate; if they already wrote to you from the new address, both records merge.",
                style = TnType.caption,
                color = colors.textMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            ModalInput(nickname, onNicknameChange, "Nickname")
            ModalInput(notes, onNotesChange, "Notes", isMultiline = true)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.End)) {
                Text("Cancel", style = TnType.body.copy(fontWeight = FontWeight.Medium), color = colors.textMuted, modifier = Modifier.clickable(role = Role.Button, onClick = onCancel))
                Text("Save", style = TnType.body.copy(fontWeight = FontWeight.Medium), color = colors.accent, modifier = Modifier.clickable(role = Role.Button, onClick = onSave).semantics { contentDescription = "Save contact" })
            }
        }
    }
}

@Composable
private fun ModalInput(value: String, onChange: (String) -> Unit, placeholder: String, isMono: Boolean = false, isMultiline: Boolean = false) {
    val colors = TnTheme.colors
    val style = if (isMono) TnType.body.copy(fontSize = 16.sp, color = colors.text, fontFamily = FontFamily.Monospace) else TnType.body.copy(fontSize = 16.sp, color = colors.text)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = !isMultiline,
        textStyle = style,
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = if (isMono) KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false) else KeyboardOptions.Default,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceRaised)
            .then(if (isMultiline) Modifier.defaultMinSize(minHeight = 72.dp) else Modifier)
            .semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = if (isMultiline) Alignment.TopStart else Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = style, color = colors.textMuted)
                inner()
            }
        },
    )
}

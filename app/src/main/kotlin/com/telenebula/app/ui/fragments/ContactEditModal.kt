package com.telenebula.app.ui.fragments

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    TnModal(isVisible = isVisible, title = "Edit contact", onDismiss = onCancel, confirmLabel = "Save", onConfirm = onSave, confirmDescription = "Save contact") {
        ModalTextField(address, onAddressChange, "Nebula IPv6 address", isMono = true)
        Text(
            "Changing the address moves this chat with it. Use it when the contact received a new certificate; if they already wrote to you from the new address, both records merge.",
            style = TnType.caption,
            color = colors.textMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
        ModalTextField(nickname, onNicknameChange, "Nickname")
        ModalTextField(notes, onNotesChange, "Notes", isMultiline = true)
    }
}

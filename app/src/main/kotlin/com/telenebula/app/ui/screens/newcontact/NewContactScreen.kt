package com.telenebula.app.ui.screens.newcontact

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.PrimaryButton
import com.telenebula.app.ui.fragments.ScannerView
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRadius
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun NewContactScreen(viewModel: NewContactViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TnTheme.colors
    Screen(title = "New Contact", onBack = viewModel::goBack, hasKeyboard = true) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg).padding(top = TnSpace.sm), verticalArrangement = Arrangement.spacedBy(TnSpace.md)) {
            Field(label = "Nickname (optional)") {
                PlainInput(state.nickname, viewModel::setNickname, placeholder = "What you call them", label = "Nickname")
            }
            Field(label = "IPv6 number (required)") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TnSpace.sm)) {
                    PlainInput(state.ip, viewModel::setIp, placeholder = "fd00:1234:5678::3", label = "IPv6 number", isMono = true, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(TnRadius.sm))
                            .background(colors.surfaceRaised)
                            .clickable(role = Role.Button, onClick = viewModel::toggleScanner)
                            .semantics { contentDescription = if (state.isScannerOpen) "Close scanner" else "Scan a contact QR code" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(TnIcon.SCAN, tint = if (state.isScannerOpen) colors.accent else colors.text, size = 22.dp) }
                }
            }
        }
        if (state.isScannerOpen) ScannerPanel(state, viewModel)
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.lg).padding(top = TnSpace.md), verticalArrangement = Arrangement.spacedBy(TnSpace.md)) {
            if (state.scannedName.isNotEmpty()) Text("Username from QR: @${state.scannedName}", style = TnType.small.copy(fontFamily = FontFamily.Monospace), color = colors.success)
            Text("The contact's nebula IPv6 is their number. Their username appears automatically once they send you something.", style = TnType.caption, color = colors.textMuted, modifier = Modifier.padding(horizontal = TnSpace.xs))
            Field(label = null) {
                PlainInput(state.notes, viewModel::setNotes, placeholder = "Notes (optional)", label = "Notes", isMultiline = true)
            }
            PrimaryButton(label = "Create Contact", onClick = viewModel::createContact, isEnabled = state.canCreate)
        }
    }
}

@Composable
private fun Field(label: String?, content: @Composable () -> Unit) {
    val colors = TnTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TnRadius.lg))
            .background(colors.surface)
            .padding(horizontal = TnSpace.lg, vertical = TnSpace.sm),
    ) {
        if (label != null) Text(label, style = TnType.caption.copy(fontWeight = FontWeight.Medium), color = colors.accent)
        content()
    }
}

@Composable
private fun PlainInput(value: String, onChange: (String) -> Unit, placeholder: String, label: String, modifier: Modifier = Modifier, isMono: Boolean = false, isMultiline: Boolean = false) {
    val colors = TnTheme.colors
    val style = if (isMono) TnType.body.copy(color = colors.text, fontFamily = FontFamily.Monospace) else TnType.body.copy(color = colors.text)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = !isMultiline,
        textStyle = style,
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = if (isMono) KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false) else KeyboardOptions.Default,
        modifier = modifier
            .fillMaxWidth()
            .then(if (isMultiline) Modifier.defaultMinSize(minHeight = 72.dp) else Modifier)
            .padding(vertical = TnSpace.sm)
            .semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(contentAlignment = if (isMultiline) Alignment.TopStart else Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = style, color = colors.textMuted)
                inner()
            }
        },
    )
}

/** Camera viewfinder for a profile QR, or the permission request when the camera is not allowed. */
@Composable
private fun ScannerPanel(state: NewContactUiState, actions: NewContactActions) {
    val colors = TnTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TnSpace.lg)
            .padding(top = TnSpace.md)
            .height(260.dp)
            .clip(RoundedCornerShape(TnRadius.lg))
            .background(colors.surface),
    ) {
        if (state.hasCameraPermission) {
            ScannerView(onScanned = actions::handleScanned, onError = actions::handleScannerError, modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 0.18f.fraction(), end = 0.18f.fraction(), top = 0.16f.fraction(260.dp), bottom = 0.22f.fraction(260.dp))
                    .border(2.dp, Color(0x88FFFFFF), RoundedCornerShape(TnRadius.lg)),
            )
            Text(
                "Point the camera at a TeleNebula profile QR",
                style = TnType.caption,
                color = Color(0xCCFFFFFF),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = TnSpace.md, vertical = TnSpace.sm),
            )
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(TnSpace.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TnSpace.md, Alignment.CenterVertically)) {
                Text("Camera access is needed to scan a QR code.", style = TnType.body, color = colors.textMuted, textAlign = TextAlign.Center)
                PrimaryButton(label = "Allow camera", onClick = actions::requestCameraPermission)
            }
        }
    }
}

// the viewfinder frame is a proportion of the panel; the panel is 260 dp tall and screen-wide
private fun Float.fraction(height: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp = height * this
private fun Float.fraction(): androidx.compose.ui.unit.Dp = (360 * this).dp

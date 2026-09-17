package com.telenebula.app.ui.fragments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/** Encodes once per value into a single path, drawn black on white in one call. */
@Composable
fun QrCode(value: String, size: Dp = 216.dp, modifier: Modifier = Modifier) {
    val matrix = remember(value) {
        QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    val path = remember(matrix) {
        Path().apply {
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) addRect(Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f))
            }
        }
    }
    Canvas(modifier = modifier.size(size).background(Color.White).semantics { contentDescription = "QR code" }) {
        val cell = this.size.width / matrix.width
        scale(cell, cell, pivot = Offset.Zero) { drawPath(path, Color.Black) }
    }
}

/** This device's contact info as a scannable code, in a centred card. */
@Composable
fun QrSheet(isVisible: Boolean, value: String, name: String, detail: String, onClose: () -> Unit) {
    val colors = TnTheme.colors
    CenteredOverlay(isVisible = isVisible, onDismiss = onClose, horizontalPadding = 36.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(name, style = TnType.body.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium), color = colors.text)
            QrCode(value, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp))
            SelectionContainer {
                Text(detail, style = TnType.small.copy(fontSize = 14.sp, fontFamily = FontFamily.Monospace), color = colors.text, textAlign = TextAlign.Center)
            }
            Text("Share this so others can add you by your IPv6 number.", style = TnType.caption.copy(fontSize = 12.5.sp), color = colors.textMuted, textAlign = TextAlign.Center)
        }
    }
}

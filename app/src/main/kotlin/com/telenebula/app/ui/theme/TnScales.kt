package com.telenebula.app.ui.theme

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 4-point spacing scale. */
object TnSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Round shapes use half their own size as radius; Android draws oversized radii as squares. */
object TnRadius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
}

/** Every list and settings row shares this height; icons sit in a 36 pt tile. */
object TnRow {
    val height = 56.dp
    val iconTile = 36.dp
    val avatar = 48.dp
}

/** Type scale with fixed line heights; weights stay at 400 and 500 only. */
object TnType {
    private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    private val platform = PlatformTextStyle(includeFontPadding = false)

    private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        lineHeightStyle = trim,
        platformStyle = platform,
    )

    val caption = style(12, 16)
    val small = style(13, 18)
    val body = style(15, 21)
    val title = style(17, 22, FontWeight.Medium)
    val heading = style(22, 28, FontWeight.Medium)
    val display = style(28, 34, FontWeight.Medium)
}

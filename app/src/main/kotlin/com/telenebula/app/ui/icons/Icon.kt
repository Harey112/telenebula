package com.telenebula.app.ui.icons

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Draws one icon of the vocabulary; [contentDescription] null when the icon is decorative. */
@Composable
fun Icon(
    icon: TnIcon,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    contentDescription: String? = null,
) {
    Icon(
        painter = rememberVectorPainter(TnIconVectors.get(icon)),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}

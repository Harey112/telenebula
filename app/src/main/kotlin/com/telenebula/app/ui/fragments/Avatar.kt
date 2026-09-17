package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.theme.ThemeResolver

/** Initial-letter identity circle; the colour is a stable hash of the name. */
@Composable
fun Avatar(name: String, size: Dp = 52.dp, modifier: Modifier = Modifier) {
    val fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(ThemeResolver.avatarColor(name))
            .semantics { contentDescription = "Avatar for $name" },
        contentAlignment = Alignment.Center,
    ) {
        Text(ThemeResolver.initialOf(name), style = TextStyle(color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Medium))
    }
}

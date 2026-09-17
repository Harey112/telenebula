package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.theme.TnTheme

/** An accent dot marking a row that has something new; [label] says what for the screen reader. */
@Composable
fun DotBadge(label: String) {
    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(TnTheme.colors.accent).semantics { contentDescription = label })
}

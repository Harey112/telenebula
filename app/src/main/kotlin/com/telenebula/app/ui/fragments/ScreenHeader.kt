package com.telenebula.app.ui.fragments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRow
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/** Flat screen header: back arrow, title, optional trailing actions. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val colors = TnTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(TnRow.height).padding(horizontal = TnSpace.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.lg),
    ) {
        if (onBack != null) {
            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.CenterStart) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(TnIcon.BACK, tint = colors.text, contentDescription = "Back")
                }
            }
        }
        Text(
            text = title,
            style = TnType.heading,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TnSpace.md)) { trailing() }
        }
    }
}

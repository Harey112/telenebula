package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/** One of the square actions under a contact's name (Ping · Message · Call · Video). */
@Composable
fun ActionButton(
    icon: TnIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** status colour for icon and label (e.g. a ping result) */
    tint: Color? = null,
    isBusy: Boolean = false,
    /** greyed out and inert (e.g. the nebula tunnel is off) */
    isEnabled: Boolean = true,
) {
    val colors = TnTheme.colors
    val color = tint ?: colors.text
    Column(
        modifier = modifier
            .alpha(if (isEnabled) 1f else 0.4f)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .clickable(enabled = isEnabled && !isBusy, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, tint = color, size = 22.dp)
        Text(label, style = TnType.small.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium), color = color, modifier = Modifier.alpha(if (isBusy) 0.6f else 1f))
    }
}

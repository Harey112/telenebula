package com.telenebula.app.ui.fragments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.app.platform.CallLogRowData
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

@Composable
fun CallLogRow(row: CallLogRowData, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val colors = TnTheme.colors
    val color = if (row.isMissed) colors.danger else colors.textMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = "${row.title}, ${row.subtitle}" }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(row.icon, tint = color, size = 18.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, style = TnType.body, color = if (row.isMissed) colors.danger else colors.text)
            Text(row.subtitle, style = TnType.caption.copy(fontSize = 12.5.sp), color = colors.textMuted, modifier = Modifier.padding(top = 1.dp))
        }
        trailing?.invoke()
    }
}

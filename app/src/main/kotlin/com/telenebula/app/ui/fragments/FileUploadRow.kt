package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** One credential file to upload; the badge turns green once it is in. */
@Composable
fun FileUploadRow(label: String, isDone: Boolean, onClick: () -> Unit, detail: String? = null) {
    val colors = TnTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$label, ${if (isDone) "uploaded" else "not uploaded"}" }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(if (isDone) colors.success else colors.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (isDone) TnIcon.CHECK else TnIcon.PLUS, tint = if (isDone) Color.White else colors.text, size = 17.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = TnType.body.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Medium), color = colors.text)
            if (detail != null) Text(detail, style = TnType.caption.copy(fontSize = 12.5.sp), color = colors.textMuted, modifier = Modifier.padding(top = 2.dp))
        }
        Text(if (isDone) "Change" else "Upload", style = TnType.small.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium), color = colors.accent)
    }
}

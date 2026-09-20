package com.telenebula.app.ui.fragments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnRow
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

/** One choice of a [SelectMenuRow]. */
data class SelectOption(val key: String, val label: String)

/** Title on the left, the current choice on the right; tapping the row opens the choices. */
@Composable
fun SelectMenuRow(
    title: String,
    options: List<SelectOption>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    placeholder: String = "Choose",
) {
    val colors = TnTheme.colors
    var isOpen by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.key == selectedKey }
    val value = selected?.label ?: placeholder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { isOpen = true }
            .defaultMinSize(minHeight = TnRow.height)
            .padding(horizontal = TnSpace.lg, vertical = TnSpace.sm)
            .semantics { contentDescription = "$title: $value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
    ) {
        Text(title, style = TnType.body, color = colors.text, modifier = Modifier.weight(1f))
        Text(value, style = TnType.body, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(TnIcon.CHEVRON_DOWN, tint = colors.textMuted, size = 16.dp)
    }
    OptionsMenu(
        isVisible = isOpen,
        title = title,
        onClose = { isOpen = false },
        options = options.map { option ->
            MenuOption(option.key, if (option.key == selectedKey) TnIcon.CHECK else TnIcon.CIRCLE, option.label) {
                isOpen = false
                onSelect(option.key)
            }
        },
    )
}

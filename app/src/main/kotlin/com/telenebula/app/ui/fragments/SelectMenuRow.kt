package com.telenebula.app.ui.fragments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Title on the left, the current choice on the right; tapping it opens that row's [SelectMenu]. */
@Composable
fun SelectMenuRow(
    title: String,
    options: List<SelectOption>,
    selectedKey: String,
    placeholder: String = "Choose",
    onClick: () -> Unit,
) {
    ValueRow(title, options.firstOrNull { it.key == selectedKey }?.label ?: placeholder, onClick)
}

/** Title on the left, a value on the right, and a tap that opens whatever changes it. */
@Composable
fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    val colors = TnTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = TnRow.height)
            .padding(horizontal = TnSpace.lg, vertical = TnSpace.sm)
            .semantics { contentDescription = "$title: $value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TnSpace.md),
    ) {
        Text(title, style = TnType.body, color = colors.text, modifier = Modifier.weight(1f))
        Text(value, style = TnType.body, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(TnIcon.CHEVRON_RIGHT, tint = colors.textMuted, size = 16.dp)
    }
}

/**
 * The choices of a [SelectMenuRow], over the whole screen like the app's other menus. A screen
 * draws it beside its body, never inside it: the body scrolls, and a menu measured against a
 * scrolling column would be laid out inside the row that opened it.
 */
@Composable
fun SelectMenu(
    title: String,
    options: List<SelectOption>,
    selectedKey: String,
    isVisible: Boolean,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
) {
    OptionsMenu(
        isVisible = isVisible,
        title = title,
        onClose = onClose,
        options = options.map { option ->
            MenuOption(option.key, if (option.key == selectedKey) TnIcon.CHECK else TnIcon.CIRCLE, option.label) {
                onClose()
                onSelect(option.key)
            }
        },
    )
}

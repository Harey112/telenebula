package com.telenebula.app.ui.fragments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telenebula.core.model.EmojiGroup
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme
import com.telenebula.app.ui.theme.TnType

private const val COLUMNS = 8

private sealed interface PickerItem {
    val key: String

    class Header(override val key: String, val title: String) : PickerItem

    class EmojiRow(override val key: String, val emojis: List<String>, val isHighlighted: Boolean) : PickerItem
}

private fun MutableList<PickerItem>.addSection(sectionKey: String, title: String, emojis: List<String>, isHighlighted: Boolean) {
    if (emojis.isEmpty()) return
    add(PickerItem.Header("h:$sectionKey", title))
    var i = 0
    while (i < emojis.size) {
        add(PickerItem.EmojiRow("$sectionKey:$i", emojis.subList(i, minOf(i + COLUMNS, emojis.size)), isHighlighted))
        i += COLUMNS
    }
}

/**
 * Your reactions and recent ones first (the current reaction highlighted there), then the whole
 * catalog by category. Rows are pre-chunked so one LazyColumn virtualises the ~1,900 emojis.
 */
@Composable
fun EmojiPicker(
    groups: List<EmojiGroup>,
    yourReactions: List<String>,
    recentReactions: List<String>,
    currentReaction: String?,
    onPick: (String) -> Unit,
) {
    val colors = TnTheme.colors
    val height = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    val items = remember(groups, yourReactions, recentReactions) {
        buildList<PickerItem> {
            addSection("yours", "Your reactions", yourReactions, isHighlighted = true)
            addSection("recent", "Recent", recentReactions, isHighlighted = true)
            for (group in groups) addSection(group.title, group.title, group.emojis, isHighlighted = false)
        }
    }
    val emojiStyle = remember { TextStyle(fontSize = 26.sp) }
    LazyColumn(modifier = Modifier.fillMaxWidth().height(height)) {
        items(items, key = { it.key }, contentType = { it is PickerItem.Header }) { item ->
            when (item) {
                is PickerItem.Header -> Text(
                    item.title.uppercase(),
                    style = TnType.small.copy(letterSpacing = 0.6.sp),
                    color = colors.textMuted,
                    modifier = Modifier.padding(top = TnSpace.md, bottom = TnSpace.xs),
                )
                is PickerItem.EmojiRow -> Row(modifier = Modifier.fillMaxWidth()) {
                    for (emoji in item.emojis) {
                        val isCurrent = item.isHighlighted && emoji == currentReaction
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(if (isCurrent) colors.accentSoft else Color.Transparent)
                                .clickable(role = Role.Button) { onPick(emoji) }
                                .semantics {
                                    contentDescription = if (isCurrent) "Remove reaction $emoji" else "React $emoji"
                                    selected = isCurrent
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji, style = emojiStyle) }
                    }
                    repeat(COLUMNS - item.emojis.size) { Box(modifier = Modifier.weight(1f).aspectRatio(1f)) }
                }
            }
        }
    }
}

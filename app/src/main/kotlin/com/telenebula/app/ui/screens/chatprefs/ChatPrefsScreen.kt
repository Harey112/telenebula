package com.telenebula.app.ui.screens.chatprefs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.ui.fragments.Screen
import com.telenebula.app.ui.fragments.Section
import com.telenebula.app.ui.fragments.SelectMenu
import com.telenebula.app.ui.fragments.SelectMenuRow
import com.telenebula.app.ui.fragments.SwitchRow
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme

@Composable
fun ChatPrefsScreen(viewModel: ChatPrefsViewModel) {
    val prefs by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = TnTheme.colors
    val emojiStyle = remember { TextStyle(fontSize = 24.sp) }
    Screen(title = "Chats", onBack = viewModel::goBack) {
        Section(title = "Reading") {
            row { SelectMenuRow("Text size", viewModel.textSizeOptions, prefs.textSizeKey) { viewModel.openMenu(TEXT_SIZE) } }
            row { SelectMenuRow("Message density", viewModel.densityOptions, prefs.densityKey) { viewModel.openMenu(DENSITY) } }
        }
        Section(title = "Sending") {
            row { SwitchRow(TnIcon.SEND, "Enter to send", prefs.isEnterToSend, viewModel::toggleEnterToSend, subtitle = "Off keeps Enter as a new line") }
        }
        Section(title = "Your reactions", footnote = "Shown first in the message menu and the reaction picker. Tap one to replace it.") {
            row {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = TnSpace.md, vertical = TnSpace.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    prefs.quickReactions.forEachIndexed { slot, emoji ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceRaised)
                                .clickable(role = Role.Button) { viewModel.editQuickReaction(slot) }
                                .semantics { contentDescription = "Replace reaction $emoji" },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji, style = emojiStyle) }
                    }
                }
            }
        }
    }
    SelectMenu("Text size", viewModel.textSizeOptions, prefs.textSizeKey, prefs.openMenuKey == TEXT_SIZE, viewModel::setChatTextSize, viewModel::closeMenu)
    SelectMenu("Message density", viewModel.densityOptions, prefs.densityKey, prefs.openMenuKey == DENSITY, viewModel::setDensity, viewModel::closeMenu)
}

private const val TEXT_SIZE = "text-size"
private const val DENSITY = "density"

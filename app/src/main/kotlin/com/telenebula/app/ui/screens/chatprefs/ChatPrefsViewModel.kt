package com.telenebula.app.ui.screens.chatprefs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Navigator
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.sheets.SheetCenter
import com.telenebula.app.sheets.SheetRequest
import com.telenebula.app.ui.fragments.SelectOption
import com.telenebula.core.model.ChatTextSize
import com.telenebula.core.model.MessageDensity
import com.telenebula.core.model.Prefs
import com.telenebula.app.ui.shared.OpenMenu
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class ChatPrefsUiState(
    val textSizeKey: String = ChatTextSize.MEDIUM.name,
    val densityKey: String = MessageDensity.COMFORTABLE.name,
    val isEnterToSend: Boolean = false,
    val quickReactions: List<String> = emptyList(),
    val openMenuKey: String? = null,
)

class ChatPrefsViewModel(
    private val prefs: PrefsRepository,
    private val sheets: SheetCenter,
    private val navigator: Navigator,
) : ViewModel() {
    private val menus = OpenMenu()

    val uiState: StateFlow<ChatPrefsUiState> = combine(prefs.prefs, menus.key, ::build)
        .uiState(viewModelScope, build(prefs.prefs.value, null))

    fun openMenu(key: String) = menus.open(key)

    fun closeMenu() = menus.close()

    val textSizeOptions = ChatTextSize.entries.map { SelectOption(it.name, it.name.lowercase()) }
    val densityOptions = MessageDensity.entries.map { SelectOption(it.name, it.name.lowercase()) }

    private fun build(p: Prefs, openMenuKey: String?) = ChatPrefsUiState(
        textSizeKey = p.app.chatTextSize.name,
        densityKey = p.app.messageDensity.name,
        isEnterToSend = p.app.isEnterToSend,
        quickReactions = p.core.quickReactions,
        openMenuKey = openMenuKey,
    )

    fun setChatTextSize(key: String) = prefs.update { it.copy(app = it.app.copy(chatTextSize = ChatTextSize.valueOf(key))) }
    fun setDensity(key: String) = prefs.update { it.copy(app = it.app.copy(messageDensity = MessageDensity.valueOf(key))) }
    fun toggleEnterToSend() = prefs.update { it.copy(app = it.app.copy(isEnterToSend = !it.app.isEnterToSend)) }
    fun goBack() = navigator.pop()

    /** Opens the whole catalog to fill one quick-reaction slot. */
    fun editQuickReaction(slot: Int) = sheets.open(SheetRequest.QuickReaction(slot))
}

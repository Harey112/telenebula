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
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

data class ChatPrefsUiState(
    val textSizeKey: String = ChatTextSize.MEDIUM.name,
    val densityKey: String = MessageDensity.COMFORTABLE.name,
    val isEnterToSend: Boolean = false,
    val quickReactions: List<String> = emptyList(),
)

class ChatPrefsViewModel(
    private val prefs: PrefsRepository,
    private val sheets: SheetCenter,
    private val navigator: Navigator,
) : ViewModel() {
    val uiState: StateFlow<ChatPrefsUiState> = prefs.prefs.map(::build)
        .uiState(viewModelScope, build(prefs.prefs.value))

    val textSizeOptions = ChatTextSize.entries.map { SelectOption(it.name, it.name.lowercase()) }
    val densityOptions = MessageDensity.entries.map { SelectOption(it.name, it.name.lowercase()) }

    private fun build(p: Prefs) = ChatPrefsUiState(
        textSizeKey = p.chatTextSize.name,
        densityKey = p.messageDensity.name,
        isEnterToSend = p.isEnterToSend,
        quickReactions = p.quickReactions,
    )

    fun setChatTextSize(key: String) = prefs.update { it.copy(chatTextSize = ChatTextSize.valueOf(key)) }
    fun setDensity(key: String) = prefs.update { it.copy(messageDensity = MessageDensity.valueOf(key)) }
    fun toggleEnterToSend() = prefs.update { it.copy(isEnterToSend = !it.isEnterToSend) }
    fun goBack() = navigator.pop()

    /** Opens the whole catalog to fill one quick-reaction slot. */
    fun editQuickReaction(slot: Int) = sheets.open(SheetRequest.QuickReaction(slot))
}

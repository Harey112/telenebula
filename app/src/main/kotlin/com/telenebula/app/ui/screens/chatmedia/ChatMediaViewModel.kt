package com.telenebula.app.ui.screens.chatmedia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Navigator
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.platform.OpenWith
import com.telenebula.app.sheets.MediaViewerCenter
import com.telenebula.app.ui.shared.PeerListUiState
import com.telenebula.app.ui.shared.openAttachment
import com.telenebula.app.ui.shared.peerListState
import com.telenebula.core.CoreClient
import com.telenebula.core.model.ChatMessage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest

class ChatMediaViewModel(
    ip: String,
    core: CoreClient,
    private val openWith: OpenWith,
    private val viewer: MediaViewerCenter,
    private val notices: NoticeCenter,
    private val navigator: Navigator,
) : ViewModel() {
    val uiState: StateFlow<PeerListUiState<ChatMessage>> =
        peerListState(ip, core, viewModelScope, core.chatChanges(ip).mapLatest { core.chatMedia(ip, 500) }, emptyList())

    fun openItem(msg: ChatMessage) {
        val target = openAttachment(msg, openWith, notices) ?: return
        viewer.open(target)
    }

    fun goBack() = navigator.pop()
}

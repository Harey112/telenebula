package com.telenebula.app.ui.screens.chatlinks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Navigator
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.platform.OpenWith
import com.telenebula.app.ui.shared.PeerListUiState
import com.telenebula.app.ui.shared.peerListState
import com.telenebula.core.CoreClient
import com.telenebula.core.model.ChatLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest

class ChatLinksViewModel(ip: String, core: CoreClient, private val openWith: OpenWith, private val notices: NoticeCenter, private val navigator: Navigator) : ViewModel() {
    val uiState: StateFlow<PeerListUiState<ChatLink>> =
        peerListState(ip, core, viewModelScope, core.chatChanges(ip).mapLatest { core.chatLinks(ip, 500) }, emptyList())

    fun openLink(url: String) {
        try {
            openWith.openUrl(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notices.addWarning("Can't open: $url")
        }
    }

    fun goBack() = navigator.pop()
}

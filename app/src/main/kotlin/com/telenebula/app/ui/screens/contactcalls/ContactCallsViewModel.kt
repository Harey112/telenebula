package com.telenebula.app.ui.screens.contactcalls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Navigator
import com.telenebula.app.platform.CallLogPresentation
import com.telenebula.app.platform.CallLogRowData
import com.telenebula.app.ui.shared.PeerListUiState
import com.telenebula.app.ui.shared.peerListState
import com.telenebula.core.CoreClient
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

class ContactCallsViewModel(ip: String, core: CoreClient, private val navigator: Navigator) : ViewModel() {
    // the cached recent calls are a prefix of the full per-peer list, so the first frame already shows them
    val uiState: StateFlow<PeerListUiState<CallLogRowData>> = peerListState(
        ip,
        core,
        viewModelScope,
        core.callLogsFlow(ip, 500).map { logs -> logs.map { CallLogPresentation.toRow(it) } },
        core.recentCallLogs.value.orEmpty().filter { it.peerIp == ip }.map { CallLogPresentation.toRow(it) },
    )

    fun goBack() = navigator.pop()
}

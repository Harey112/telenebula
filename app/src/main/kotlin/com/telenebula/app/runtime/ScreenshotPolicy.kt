package com.telenebula.app.runtime

import com.telenebula.app.nav.ChatScopedKey
import com.telenebula.app.nav.Navigator
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.core.CoreClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Whether the window may be captured right now: the open chat's own setting, else the global one. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenshotPolicy(prefs: PrefsRepository, core: CoreClient, navigator: Navigator, scope: CoroutineScope) {
    private val openChatOverride = navigator.topKey
        .map { (it as? ChatScopedKey)?.peerIp }
        .flatMapLatest { ip -> if (ip == null) flowOf(null) else core.contactFlow(ip).map { it?.privacy?.blockScreenshots } }

    val isBlocked: StateFlow<Boolean> = combine(prefs.prefs.map { it.isScreenshotBlocked }, openChatOverride) { global, override ->
        override ?: global
    }.stateIn(scope, SharingStarted.Eagerly, prefs.prefs.value.isScreenshotBlocked)
}

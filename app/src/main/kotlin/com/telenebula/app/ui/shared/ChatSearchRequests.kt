package com.telenebula.app.ui.shared

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * "Open the search field in this chat", asked for from somewhere that is not the chat.
 *
 * Chat settings sits on top of the chat it belongs to, so getting back there is a pop rather than a
 * push — and a pop carries nothing. Widening the `Chat` nav key instead would change the identity
 * the back stack compares by, which `push` and `popTo` both rely on. A one-shot signal is the
 * smaller thing to get wrong.
 */
class ChatSearchRequests {
    private val requests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val peerIps: SharedFlow<String> = requests.asSharedFlow()

    fun open(peerIp: String) {
        requests.tryEmit(peerIp)
    }
}

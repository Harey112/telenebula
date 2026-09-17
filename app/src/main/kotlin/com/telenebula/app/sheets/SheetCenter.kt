package com.telenebula.app.sheets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the root bottom sheet should show, as data; the host decides how. */
sealed interface SheetRequest {
    val title: String

    class MessageActivity(val messageId: String, val peerIp: String) : SheetRequest {
        override val title get() = "Message activity"
    }

    class Reactions(val messageId: String, val peerIp: String) : SheetRequest {
        override val title get() = "Reactions"
    }

    class ReactionPicker(val messageId: String, val peerIp: String) : SheetRequest {
        override val title get() = "React"
    }

    class QuickReaction(val slot: Int) : SheetRequest {
        override val title get() = "Choose a reaction"
    }
}

class SheetCenter {
    private val mutable = MutableStateFlow<SheetRequest?>(null)
    val request: StateFlow<SheetRequest?> = mutable.asStateFlow()

    fun open(request: SheetRequest) {
        mutable.value = request
    }

    fun close() {
        mutable.value = null
    }
}

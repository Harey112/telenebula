package com.telenebula.web.state

import com.telenebula.web.wire.DexCallState
import com.telenebula.web.wire.DexChat
import com.telenebula.web.wire.DexChatView
import com.telenebula.web.wire.DexContact
import com.telenebula.web.wire.DexIdentity
import com.telenebula.web.wire.DexMessage
import com.telenebula.web.wire.DexNoticeLevel
import com.telenebula.web.wire.DexPresence
import com.telenebula.web.wire.DexQueue
import org.w3c.files.Blob

sealed interface Screen {
    data object Loading : Screen
    data class Login(val error: String? = null, val isBusy: Boolean = false) : Screen
    data object App : Screen
}

enum class Connection { CONNECTING, CONNECTED, RECONNECTING }

data class Toast(val id: Int, val level: DexNoticeLevel, val message: String)

data class Upload(val id: Int, val name: String, val pct: Int, val error: String? = null)

/** A clip being recorded or waiting to be sent; the blob is a browser object, compared by identity. */
sealed interface Recording {
    data class Live(val startedAt: Long) : Recording
    class Stopped(val blob: Blob, val mime: String, val durationMs: Long, val url: String) : Recording
}

data class Composer(
    val replyTo: DexMessage? = null,
    val isCovered: Boolean = false,
    val editing: DexMessage? = null,
    val isEmojiOpen: Boolean = false,
)

data class Prompt(val message: String, val confirmLabel: String, val onConfirm: () -> Unit)

data class AppState(
    val screen: Screen = Screen.Loading,
    val me: DexIdentity? = null,
    val clientId: String? = null,
    val freeBytes: Long = 0,
    val connection: Connection = Connection.CONNECTING,
    val chats: List<DexChat> = emptyList(),
    val contacts: Map<String, DexContact> = emptyMap(),
    val presence: Map<String, DexPresence> = emptyMap(),
    val typing: Set<String> = emptySet(),
    val queues: Map<String, DexQueue> = emptyMap(),
    val openPeer: String? = null,
    val view: DexChatView? = null,
    val isLoadingMore: Boolean = false,
    val revealed: Set<String> = emptySet(),
    val composer: Composer = Composer(),
    val uploads: List<Upload> = emptyList(),
    val recording: Recording? = null,
    val call: DexCallState = DexCallState(),
    val toasts: List<Toast> = emptyList(),
    val lightbox: String? = null,
    val showArchived: Boolean = false,
    val search: String = "",
    val menuFor: String? = null,
    val reactFor: String? = null,
    val prompt: Prompt? = null,
    val hasNewBelow: Boolean = false,
    val isCallMediaSupported: Boolean = true,
) {
    val isMySeat: Boolean get() = call.seat == com.telenebula.web.wire.DexSeat.DEX && call.seatClientId != null && call.seatClientId == clientId
    val unreadTotal: Int get() = chats.filter { !it.isArchived }.sumOf { it.unread }
}

/** One value, replaced whole; listeners run once per animation frame however many updates land. */
class Store(initial: AppState, private val schedule: (() -> Unit) -> Unit) {
    var state: AppState = initial
        private set
    private var isScheduled = false
    private val listeners = ArrayList<(AppState, AppState) -> Unit>()
    private var rendered: AppState = initial

    fun update(transform: (AppState) -> AppState) {
        state = transform(state)
        if (isScheduled) return
        isScheduled = true
        schedule {
            isScheduled = false
            val previous = rendered
            val next = state
            rendered = next
            if (previous === next) return@schedule
            for (l in listeners.toList()) {
                try {
                    l(previous, next)
                } catch (e: Throwable) {
                    console.error("render failed", e)
                }
            }
        }
    }

    fun listen(listener: (AppState, AppState) -> Unit) {
        listeners.add(listener)
    }
}

package com.telenebula.web.state

import com.telenebula.web.net.EmojiGroup
import com.telenebula.web.wire.DexAccount
import com.telenebula.web.wire.DexCallLog
import com.telenebula.web.wire.DexCallState
import com.telenebula.web.wire.DexChat
import com.telenebula.web.wire.DexChatLink
import com.telenebula.web.wire.DexContactDetail
import com.telenebula.web.wire.DexDiagnostics
import com.telenebula.web.wire.DexNetwork
import com.telenebula.web.wire.DexPingResult
import com.telenebula.web.wire.DexSettings
import com.telenebula.web.wire.DexStorage
import com.telenebula.web.wire.DexUpdates
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

/** The left rail's destinations; the phone's bottom tabs, given a desktop's room. */
enum class Tab(val label: String) {
    CHATS("Chats"),
    CONTACTS("Contacts"),
    CALLS("Calls"),
    SETTINGS("Settings"),
}

/** One pane of the preferences window, each the phone's screen of the same name. */
enum class SettingsTab(val label: String, val sections: List<String> = emptyList()) {
    ACCOUNT("Account", listOf("account")),
    STATUS("Status", listOf("network")),
    APPEARANCE("Appearance"),
    CHATS("Chats"),
    NOTIFICATIONS("Notifications"),
    PRIVACY("Privacy"),
    CALLS("Calls"),
    NETWORK("Network", listOf("network", "account")),
    STORAGE("Storage", listOf("storage")),
    DIAGNOSTICS("Diagnostics", listOf("diagnostics", "network")),
    UPDATES("Updates", listOf("updates")),
    DEX("Dex"),
}

enum class CallFilter(val label: String) { ALL("All"), MISSED("Missed"), IN("Incoming"), OUT("Outgoing") }

/** A form the shell draws over everything; the fields it carries are its own. */
sealed interface Dialog {
    data class AddContact(val ip: String = "", val name: String = "", val nickname: String = "", val notes: String = "", val error: String? = null) : Dialog
    data class EditContact(val peer: String, val name: String, val nickname: String, val notes: String) : Dialog
    data class ChangeIp(val peer: String, val newIp: String, val error: String? = null) : Dialog
    data class EmojiPick(val target: EmojiTarget) : Dialog
    data class MuteFor(val peer: String) : Dialog
    data class Disappearing(val peer: String) : Dialog
}

/** What a picked emoji is for; the dialog is the same either way. */
sealed interface EmojiTarget {
    data class Slot(val slot: Int) : EmojiTarget
    data class React(val messageId: String, val peer: String) : EmojiTarget
}

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
    val tab: Tab = Tab.CHATS,
    val settingsTab: SettingsTab = SettingsTab.ACCOUNT,
    val settings: DexSettings? = null,
    val account: DexAccount? = null,
    val network: DexNetwork? = null,
    val storage: DexStorage? = null,
    val diagnostics: DexDiagnostics? = null,
    val updates: DexUpdates? = null,
    val callLogs: List<DexCallLog> = emptyList(),
    val callFilter: CallFilter = CallFilter.ALL,
    val callSearch: String = "",
    val callSelection: Set<String> = emptySet(),
    val contactSearch: String = "",
    val selectedContact: String? = null,
    val contactDetail: DexContactDetail? = null,
    val chatMedia: List<DexMessage> = emptyList(),
    val chatMediaPeer: String? = null,
    val chatLinks: List<DexChatLink> = emptyList(),
    val chatLinksPeer: String? = null,
    val isInfoOpen: Boolean = false,
    val pings: Map<String, DexPingResult> = emptyMap(),
    val chatSearchResults: List<DexMessage>? = null,
    val dialog: Dialog? = null,
    val isRailOpen: Boolean = false,
    val emoji: List<EmojiGroup> = emptyList(),
) {
    val isMySeat: Boolean get() = call.seat == com.telenebula.web.wire.DexSeat.DEX && call.seatClientId != null && call.seatClientId == clientId
    val unreadTotal: Int get() = chats.filter { !it.isArchived }.sumOf { it.unread }

    /** the phone's own six; the constant only stands in until its settings land */
    val quickReactions: List<String>
        get() = settings?.quickReactions?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: com.telenebula.web.ui.DEFAULT_QUICK_REACTIONS

    /** the one place the browser's own settings are resolved against the app's */
    val effective: Effective get() = Effective.of(settings)

    val isEnterToSend: Boolean get() = effective.isEnterToSend
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

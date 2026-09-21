package com.telenebula.web.ui

import com.telenebula.web.state.Actions
import com.telenebula.web.state.AppState
import com.telenebula.web.util.Format
import com.telenebula.web.wire.DexChat
import com.telenebula.web.wire.DexDirection
import com.telenebula.web.wire.DexMessageStatus
import com.telenebula.web.wire.DexPresence
import com.telenebula.web.wire.DexSendState
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/** One chat row's inputs, so a row re-renders only when something it shows changed. */
data class ChatRow(val chat: DexChat, val presence: DexPresence?, val isTyping: Boolean, val isOpen: Boolean)

class ChatListView(root: HTMLElement, private val actions: Actions) {
    val host = div("pane pane-list").also { root.appendChild(it) }
    private val search = el("input", "search") { setAttribute("type", "search"); setAttribute("placeholder", "Search"); setAttribute("aria-label", "Search chats") } as HTMLInputElement
    private val archivedToggle = button("chip", "Show archived chats", { actions.toggleArchived() }, Icon.ARCHIVE, "Archived")
    private val listHost = div("chat-list").also { it.setAttribute("role", "list") }
    private val empty = div("empty", "No chats yet. Start one from your phone.")
    private val list = KeyedList<ChatRow>(listHost, ::create, ::update)

    init {
        host.add(div("list-tools").add(div("search-wrap").add(svg(Icon.SEARCH, 16), search), archivedToggle), listHost, empty)
        search.on("input") { actions.setSearch(search.value) }
    }

    fun render(prev: AppState, next: AppState) {
        if (prev.chats === next.chats && prev.presence === next.presence && prev.typing === next.typing && prev.openPeer == next.openPeer && prev.search == next.search && prev.showArchived == next.showArchived) return
        archivedToggle.toggle("on", next.showArchived)
        archivedToggle.setAttribute("aria-pressed", next.showArchived.toString())
        val query = next.search.trim().lowercase()
        val rows = next.chats
            .filter { it.isArchived == next.showArchived && it.lastTs != null || (it.peer == next.openPeer && !next.showArchived && !it.isArchived) }
            .filter { query.isEmpty() || it.label.lowercase().contains(query) || it.peer.contains(query) }
            .sortedWith(compareByDescending<DexChat> { it.isPinned }.thenByDescending { it.lastTs ?: 0L })
            .map { ChatRow(it, next.presence[it.peer], it.peer in next.typing, it.peer == next.openPeer) }
        list.render(rows) { it.chat.peer }
        empty.toggle("hidden", rows.isNotEmpty())
        empty.textContent = when {
            rows.isNotEmpty() -> ""
            query.isNotEmpty() -> "Nothing matches “${next.search.trim()}”."
            next.showArchived -> "No archived chats."
            else -> "No chats yet. Start one from your phone."
        }
    }

    private fun create(row: ChatRow): HTMLElement {
        val node = div("chat-row").also { it.setAttribute("role", "listitem"); it.tabIndex = 0 }
        node.add(
            div("avatar").add(span("avatar-letter"), span("presence-dot")),
            div("chat-main").add(
                div("chat-top").add(span("chat-name"), span("chat-time")),
                div("chat-bottom").add(span("chat-snippet"), span("chat-badges")),
            ),
        )
        node.on("click") { actions.openChat(row.chat.peer) }
        node.on("keydown") { e -> if ((e as? org.w3c.dom.events.KeyboardEvent)?.key == "Enter") actions.openChat(row.chat.peer) }
        update(node, row, row)
        return node
    }

    private fun update(node: HTMLElement, @Suppress("UNUSED_PARAMETER") previous: ChatRow, row: ChatRow) {
        val c = row.chat
        node.toggle("open", row.isOpen)
        node.toggle("unread", c.unread > 0 || c.isMarkedUnread)
        node.setAttribute("aria-label", "${c.label}${if (c.unread > 0) ", ${c.unread} unread" else ""}")
        node.querySelector(".avatar-letter")?.textContent = c.label.take(1).uppercase()
        (node.querySelector(".presence-dot") as? HTMLElement)?.let { dot ->
            dot.className = "presence-dot " + when (row.presence) {
                DexPresence.ONLINE -> "online"
                DexPresence.REACHABLE -> "reachable"
                else -> "offline"
            }
        }
        node.querySelector(".chat-name")?.textContent = c.label
        node.querySelector(".chat-time")?.textContent = c.lastTs?.let(Format::listTime).orEmpty()
        (node.querySelector(".chat-snippet") as? HTMLElement)?.let { s ->
            s.clear()
            if (row.isTyping) {
                s.appendChild(span("typing", "typing…"))
            } else {
                if (c.lastDir == DexDirection.OUT) s.appendChild(tick(c.lastStatus, c.lastSend))
                s.appendChild(span(null, snippet(c)))
            }
        }
        (node.querySelector(".chat-badges") as? HTMLElement)?.let { b ->
            b.clear()
            if (c.isPinned) b.appendChild(span("badge-icon").add(svg(Icon.PIN, 14)))
            if (c.isMuted) b.appendChild(span("badge-icon").add(svg(Icon.BELL_OFF, 14)))
            if (c.unread > 0) b.appendChild(span("unread-badge", if (c.unread > 99) "99+" else c.unread.toString()))
            else if (c.isMarkedUnread) b.appendChild(span("unread-badge dot"))
        }
    }

    private fun tick(status: DexMessageStatus?, send: DexSendState?): HTMLElement {
        val s = span("tick")
        when {
            send == DexSendState.FAILED -> s.textContent = "!"
            send != null || status == DexMessageStatus.PENDING -> s.appendChild(svg(Icon.CLOCK, 13))
            status == DexMessageStatus.DELIVERED || status == DexMessageStatus.RECEIVED -> s.appendChild(svg(Icon.CHECK_CHECK, 13))
            else -> s.appendChild(svg(Icon.CHECK, 13))
        }
        return s
    }

    private fun snippet(c: DexChat): String {
        val body = c.lastBody ?: return ""
        return body
    }
}

/** The shell: top bar, then the two panes. */
class ShellView(root: HTMLElement, private val actions: Actions) {
    val host = div("shell hidden").also { root.appendChild(it) }
    private val meName = span("me-name")
    private val meIp = span("me-ip")
    private val dot = span("conn-dot").also { it.setAttribute("role", "img") }
    private val connLabel = span("conn-label")
    private val panes = div("panes")
    val callBannerSlot = div("banner-slot")

    init {
        val bar = div("topbar").add(
            div("brand").add(svg(Icon.MONITOR, 18), span("brand-text", "Dex")),
            div("me").add(meName, meIp),
            div("conn").add(dot, connLabel),
            button("icon-btn", "Log out", { actions.logout() }, Icon.LOGOUT),
        )
        host.add(bar, callBannerSlot, panes)
    }

    fun paneHost(): HTMLElement = panes

    fun render(prev: AppState, next: AppState) {
        host.toggle("hidden", next.screen !is com.telenebula.web.state.Screen.App)
        if (prev.me !== next.me) {
            meName.textContent = next.me?.name.orEmpty()
            meIp.textContent = next.me?.ip.orEmpty()
        }
        if (prev.connection != next.connection) {
            dot.className = "conn-dot " + next.connection.name.lowercase()
            val label = when (next.connection) {
                com.telenebula.web.state.Connection.CONNECTED -> "Connected"
                com.telenebula.web.state.Connection.CONNECTING -> "Connecting…"
                com.telenebula.web.state.Connection.RECONNECTING -> "Reconnecting…"
            }
            dot.setAttribute("aria-label", label)
            connLabel.textContent = label
        }
        if (prev.openPeer != next.openPeer) panes.toggle("chat-open", next.openPeer != null)
    }
}

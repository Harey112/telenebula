package com.telenebula.web.ui

import com.telenebula.web.state.Actions
import com.telenebula.web.state.AppState
import com.telenebula.web.state.Dialog
import com.telenebula.web.state.Tab
import com.telenebula.web.util.Format
import com.telenebula.web.wire.DexContact
import com.telenebula.web.wire.DexContactDetail
import com.telenebula.web.wire.DexContactFlags
import com.telenebula.web.wire.DexPresence
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Date

private data class ContactRow(val contact: DexContact, val presence: DexPresence?, val isOpen: Boolean)

/** The phone's Contacts tab and its contact screen, side by side. */
class ContactsView(root: HTMLElement, private val actions: Actions) {
    private val host = div("pane pane-contacts hidden").also { root.appendChild(it) }
    private val search = el("input", "search") {
        setAttribute("type", "search")
        setAttribute("placeholder", "Search contacts")
        setAttribute("aria-label", "Search contacts")
    } as HTMLInputElement
    private val listHost = div("contact-list").also { it.setAttribute("role", "list") }
    private val empty = div("empty", "No contacts yet.")
    private val detail = div("contact-detail")
    private val list = KeyedList<ContactRow>(listHost, ::create, ::update)

    init {
        val side = div("contacts-side").add(
            div("list-tools").add(
                div("search-wrap").add(svg(Icon.SEARCH, 16), search),
                button("icon-btn", "Add a contact", { actions.openDialog(Dialog.AddContact()) }, Icon.PLUS),
            ),
            listHost,
            empty,
        )
        host.add(side, detail)
        search.on("input") { actions.setContactSearch(search.value) }
    }

    fun render(prev: AppState, next: AppState) {
        host.toggle("hidden", next.tab != Tab.CONTACTS)
        if (next.tab != Tab.CONTACTS) return
        if (prev.contacts !== next.contacts || prev.presence !== next.presence || prev.contactSearch != next.contactSearch || prev.selectedContact != next.selectedContact || prev.tab != next.tab) {
            val query = next.contactSearch.trim().lowercase()
            val rows = next.contacts.values
                .filter { query.isEmpty() || it.label.lowercase().contains(query) || it.ip.contains(query) || it.name.lowercase().contains(query) }
                .sortedBy { it.label.lowercase() }
                .map { ContactRow(it, next.presence[it.ip], it.ip == next.selectedContact) }
            list.render(rows) { it.contact.ip }
            empty.toggle("hidden", rows.isNotEmpty())
            empty.textContent = if (query.isEmpty()) "No contacts yet." else "Nothing matches “${next.contactSearch.trim()}”."
        }
        val detailChanged = prev.selectedContact != next.selectedContact ||
            prev.contactDetail != next.contactDetail ||
            prev.tab != next.tab ||
            prev.pings != next.pings
        if (detailChanged) renderDetail(next)
    }

    private fun create(row: ContactRow): HTMLElement {
        val node = div("contact-row").also {
            it.setAttribute("role", "listitem")
            it.tabIndex = 0
        }
        node.add(
            div("avatar").add(span("avatar-letter"), span("presence-dot")),
            div("contact-main").add(div("contact-name"), div("contact-ip mono")),
            span("contact-badges"),
        )
        node.on("click") { actions.selectContact(row.contact.ip) }
        node.on("keydown") { e -> if ((e as? org.w3c.dom.events.KeyboardEvent)?.key == "Enter") actions.selectContact(row.contact.ip) }
        update(node, row, row)
        return node
    }

    private fun update(node: HTMLElement, @Suppress("UNUSED_PARAMETER") previous: ContactRow, row: ContactRow) {
        val c = row.contact
        node.toggle("open", row.isOpen)
        node.setAttribute("aria-label", c.label)
        node.querySelector(".avatar-letter")?.textContent = c.label.take(1).uppercase()
        (node.querySelector(".presence-dot") as? HTMLElement)?.className = "presence-dot " + when (row.presence) {
            DexPresence.ONLINE -> "online"
            DexPresence.REACHABLE -> "reachable"
            else -> "offline"
        }
        node.querySelector(".contact-name")?.textContent = c.label
        node.querySelector(".contact-ip")?.textContent = c.ip
        (node.querySelector(".contact-badges") as? HTMLElement)?.let { b ->
            b.clear()
            if (c.isPinned) b.add(span("badge-icon").add(svg(Icon.PIN, 14)))
            if (c.muteUntil != 0L) b.add(span("badge-icon").add(svg(Icon.BELL_OFF, 14)))
            if (c.isBlocked) b.add(span("badge-icon danger").add(svg(Icon.BLOCK, 14)))
        }
    }

    private fun renderDetail(state: AppState) {
        detail.clear()
        val peer = state.selectedContact
        if (peer == null) {
            detail.add(div("empty", "Pick a contact to see everything about them."))
            return
        }
        val d = state.contactDetail?.takeIf { it.contact.ip == peer }
        val c = state.contacts[peer] ?: d?.contact
        if (c == null) {
            detail.add(div("empty", "Loading…"))
            return
        }
        detail.add(header(c, d, state))
        detail.add(
            section("Details") {
                add(infoRow("Name", c.name))
                add(infoRow("Nickname", c.nickname))
                add(infoRow("Overlay address", c.ip, isMono = true))
                add(infoRow("Added", if (c.addedAt == 0L) "" else Format.listTime(c.addedAt)))
                add(infoRow("Last seen", c.lastSeenAt?.let { Format.lastSeen(it) } ?: "—"))
                if (c.notes.isNotEmpty()) add(infoRow("Notes", c.notes, isWrapped = true))
            },
        )
        if (d != null) {
            val ping = state.pings[peer]
            detail.add(
                section("Connection") {
                    add(infoRow("Status", d.connectionStatus.ifEmpty { if (d.stats.isConnected) "Connected" else "Not connected" }))
                    add(infoRow("Endpoint", d.endpoint, isMono = true))
                    add(infoRow("Their app", d.clientVersion))
                    add(infoRow("Their certificate", d.peerCertName))
                    if (d.peerCertFingerprint.isNotEmpty()) add(infoRow("Fingerprint", d.peerCertFingerprint, isMono = true, isWrapped = true))
                    add(infoRow("Outbox", "${d.queued} queued · ${d.failed} failed"))
                    add(
                        actionRow(
                            "Reachability",
                            ping?.let { p -> if (p.error != null) p.error else "Answered in ${p.rttMs} ms" },
                            button = "Ping",
                        ) { actions.pingPeer(peer) },
                    )
                },
            )
            val s = d.stats
            detail.add(
                section("Between you") {
                    add(infoRow("Messages", "↑ ${s.messagesSent}   ↓ ${s.messagesReceived}", isMono = true))
                    add(infoRow("Media", "↑ ${s.mediaSent}   ↓ ${s.mediaReceived}", isMono = true))
                    add(infoRow("Data", "↑ ${Format.bytes(s.bytesSent)}   ↓ ${Format.bytes(s.bytesReceived)}", isMono = true))
                    add(infoRow("First message", s.firstMessageAt?.let { Format.listTime(it) } ?: "—"))
                    add(infoRow("Last activity", s.lastActivityAt?.let { Format.listTime(it) } ?: "—"))
                },
            )
            if (d.calls.isNotEmpty()) {
                val rows = div("rows")
                for (log in d.calls.take(10)) rows.add(callRow(log))
                detail.add(div("section").add(div("section-title", "Recent calls"), rows))
            }
        }
        detail.add(
            section("Manage", "Deleting a contact leaves the conversation on the phone; clearing history removes the messages.") {
                add(switchRow("Pinned", null, c.isPinned) { v -> actions.setContactFlags(peer, DexContactFlags(isPinned = v)) })
                add(switchRow("Archived", null, c.isArchived) { v -> actions.setContactFlags(peer, DexContactFlags(isArchived = v)) })
                add(switchRow("Blocked", null, c.isBlocked) { v -> actions.setContactFlags(peer, DexContactFlags(isBlocked = v)) })
                add(actionRow("Muted", muteLabel(c.muteUntil), button = if (c.muteUntil == 0L) "Mute" else "Change") { actions.openDialog(Dialog.MuteFor(peer)) })
                add(actionRow("Change overlay address", c.ip, button = "Change") { actions.openDialog(Dialog.ChangeIp(peer, c.ip)) })
                add(actionRow("Clear history", "Every message with this contact", button = "Clear", isDanger = true) { actions.clearHistory(peer) })
                add(actionRow("Delete contact", null, button = "Delete", isDanger = true) { actions.deleteContact(peer) })
            },
        )
    }

    private fun muteLabel(until: Long): String = when {
        until == 0L -> "Not muted"
        until < 0L -> "Muted"
        until < Date.now().toLong() -> "Not muted"
        else -> "Muted until ${Format.listTime(until)} ${Format.clock(until)}"
    }

    private fun callRow(log: com.telenebula.web.wire.DexCallLog): HTMLElement {
        val icon = when {
            log.outcome == com.telenebula.web.wire.DexCallOutcome.MISSED -> Icon.CALL_MISSED
            log.dir == com.telenebula.web.wire.DexDirection.IN -> Icon.CALL_IN
            else -> Icon.CALL_OUT
        }
        val duration = log.connectedAt?.let { Format.duration(log.endedAt - it) } ?: log.outcome.name.lowercase()
        return div("row row-info").add(
            div("row-text").add(
                div("row-label").add(svg(icon, 15), span(null, " ${Format.listTime(log.startedAt)} ${Format.clock(log.startedAt)}")),
                div("row-sub", (if (log.isVideo) "Video · " else "Voice · ") + duration),
            ),
        )
    }

    private fun header(c: DexContact, d: DexContactDetail?, state: AppState): HTMLElement {
        val presence = d?.presence ?: state.presence[c.ip] ?: DexPresence.OFFLINE
        val sub = when (presence) {
            DexPresence.ONLINE -> "Online"
            DexPresence.REACHABLE -> "Reachable"
            DexPresence.OFFLINE -> c.lastSeenAt?.let { Format.lastSeen(it) } ?: "Offline"
        }
        return div("detail-header").add(
            div("avatar big").add(span("avatar-letter", c.label.take(1).uppercase())),
            div("detail-id").add(div("detail-name", c.label), div("detail-sub", sub)),
            div("detail-actions").add(
                button("icon-btn", "Message", { actions.openChat(c.ip) }, Icon.CHATS),
                button("icon-btn", "Voice call", { actions.callPeer(c.ip, false) }, Icon.CALL),
                button("icon-btn", "Video call", { actions.callPeer(c.ip, true) }, Icon.VIDEO),
                button("icon-btn", "Edit", { actions.openDialog(Dialog.EditContact(c.ip, c.name, c.nickname, c.notes)) }, Icon.PENCIL),
            ),
        )
    }
}

package com.telenebula.web.ui

import com.telenebula.web.net.Api
import com.telenebula.web.state.Actions
import com.telenebula.web.state.AppState
import com.telenebula.web.state.Dialog
import com.telenebula.web.state.Tab
import com.telenebula.web.util.Format
import com.telenebula.web.wire.DexContactFlags
import com.telenebula.web.wire.DexContactNotifications
import com.telenebula.web.wire.DexMessageKind
import com.telenebula.web.wire.DexPresence
import com.telenebula.web.wire.DexRevealGate
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/** The chat's right-hand pane: everything the phone's chat-settings screen holds, beside the chat. */
class ChatInfoView(root: HTMLElement, private val actions: Actions) {
    private val host = div("pane pane-info hidden").also { root.appendChild(it) }
    private val body = div("info-body")

    init {
        host.add(
            div("info-head").add(div("pane-heading", "Details"), button("icon-btn", "Close details", { actions.toggleInfo() }, Icon.CLOSE)),
            body,
        )
    }

    fun render(prev: AppState, next: AppState) {
        val shown = next.tab == Tab.CHATS && next.isInfoOpen && next.openPeer != null
        host.toggle("hidden", !shown)
        if (!shown) return
        val changed = prev.isInfoOpen != next.isInfoOpen ||
            prev.openPeer != next.openPeer ||
            prev.contactDetail != next.contactDetail ||
            prev.chatMedia !== next.chatMedia ||
            prev.chatLinks !== next.chatLinks ||
            prev.queues !== next.queues ||
            prev.presence !== next.presence ||
            prev.pings != next.pings ||
            prev.contacts !== next.contacts ||
            prev.chatSearchResults !== next.chatSearchResults ||
            prev.tab != next.tab
        if (!changed) return
        body.clear()
        val peer = next.openPeer ?: return
        val detail = next.contactDetail?.takeIf { it.contact.ip == peer }
        val contact = next.contacts[peer] ?: detail?.contact
        val label = contact?.label ?: next.view?.contact?.label ?: peer
        val presence = detail?.presence ?: next.presence[peer] ?: DexPresence.OFFLINE
        body.add(
            div("detail-header").add(
                div("avatar big").add(span("avatar-letter", label.take(1).uppercase())),
                div("detail-id").add(
                    div("detail-name", label),
                    div(
                        "detail-sub",
                        when (presence) {
                            DexPresence.ONLINE -> "Online"
                            DexPresence.REACHABLE -> "Reachable"
                            DexPresence.OFFLINE -> contact?.lastSeenAt?.let { Format.lastSeen(it) } ?: "Offline"
                        },
                    ),
                ),
            ),
        )
        val queue = next.queues[peer]
        val ping = next.pings[peer]
        body.add(
            section("Connection") {
                add(infoRow("Overlay address", peer, isMono = true))
                add(infoRow("Outbox", if (queue == null) "Nothing waiting" else "${queue.queued} queued${if (queue.isDraining) " · sending" else ""}${if (queue.isReachable) " · reachable" else ""}"))
                if (queue != null && queue.queued > 0) {
                    add(
                        actionRow(
                            "Waiting to send",
                            "Try the queue now instead of waiting for the next round.",
                            "Send now",
                            isEnabled = !queue.isDraining,
                        ) { actions.drain(peer) },
                    )
                }
                add(actionRow("Reachability", ping?.let { p -> p.error ?: "Answered in ${p.rttMs} ms" }, button = "Ping") { actions.pingPeer(peer) })
            },
        )
        if (contact != null) {
            body.add(
                section("This chat") {
                    add(
                        actionRow(
                            "Disappearing messages",
                            if (contact.disappearSeconds == 0) "Off" else Format.duration(contact.disappearSeconds * 1000L) + " after reading",
                            button = "Change",
                        ) { actions.openDialog(Dialog.Disappearing(peer)) },
                    )
                    add(switchRow("Pinned", null, contact.isPinned) { v -> actions.setContactFlags(peer, DexContactFlags(isPinned = v)) })
                    add(switchRow("Archived", null, contact.isArchived) { v -> actions.setContactFlags(peer, DexContactFlags(isArchived = v)) })
                    add(switchRow("Blocked", null, contact.isBlocked) { v -> actions.setContactFlags(peer, DexContactFlags(isBlocked = v)) })
                    add(actionRow("Muted", if (contact.muteUntil == 0L) "Not muted" else "Muted", button = if (contact.muteUntil == 0L) "Mute" else "Change") { actions.openDialog(Dialog.MuteFor(peer)) })
                },
            )
            val p = detail?.privacy
            body.add(
                section("Just for this chat", "Each follows your global setting unless you change it here.") {
                    add(triStateRow("Read receipts", null, p?.sendReadReceipts) { v -> actions.setContactPrivacy(peer, com.telenebula.web.wire.DexContactPrivacy(sendReadReceipts = v, sendTypingIndicators = p?.sendTypingIndicators, blockScreenshots = p?.blockScreenshots, revealGate = p?.revealGate)) })
                    add(triStateRow("Typing indicators", null, p?.sendTypingIndicators) { v -> actions.setContactPrivacy(peer, com.telenebula.web.wire.DexContactPrivacy(sendReadReceipts = p?.sendReadReceipts, sendTypingIndicators = v, blockScreenshots = p?.blockScreenshots, revealGate = p?.revealGate)) })
                    add(triStateRow("Block screenshots", null, p?.blockScreenshots) { v -> actions.setContactPrivacy(peer, com.telenebula.web.wire.DexContactPrivacy(sendReadReceipts = p?.sendReadReceipts, sendTypingIndicators = p?.sendTypingIndicators, blockScreenshots = v, revealGate = p?.revealGate)) })
                    add(
                        selectRow(
                            "Reveal covered messages with",
                            null,
                            listOf(Choice("global", "Follow global"), Choice("TAP", "Just tap"), Choice("ASK", "Ask first"), Choice("CODE", "A code"), Choice("DEVICE", "The phone's lock")),
                            p?.revealGate?.name ?: "global",
                        ) { key ->
                            val gate = if (key == "global") null else DexRevealGate.valueOf(key)
                            actions.setContactPrivacy(peer, com.telenebula.web.wire.DexContactPrivacy(sendReadReceipts = p?.sendReadReceipts, sendTypingIndicators = p?.sendTypingIndicators, blockScreenshots = p?.blockScreenshots, revealGate = gate))
                        },
                    )
                },
            )
            val n = detail?.notifications
            body.add(
                section("Notifications for this chat") {
                    add(switchRow("Follow the global settings", null, n == null || n.useGlobal) { v -> actions.setContactNotifications(peer, if (v) null else DexContactNotifications(useGlobal = false)) })
                    if (n != null && !n.useGlobal) {
                        add(switchRow("Messages", null, n.messages) { v -> actions.setContactNotifications(peer, n.copy(messages = v)) })
                        add(switchRow("Show a preview", null, n.preview) { v -> actions.setContactNotifications(peer, n.copy(preview = v)) })
                        add(switchRow("Sound", null, n.sound) { v -> actions.setContactNotifications(peer, n.copy(sound = v)) })
                        add(switchRow("Vibrate", null, n.vibrate) { v -> actions.setContactNotifications(peer, n.copy(vibrate = v)) })
                        add(switchRow("Pop up on screen", null, n.popup) { v -> actions.setContactNotifications(peer, n.copy(popup = v)) })
                        add(switchRow("Reactions", null, n.reactions) { v -> actions.setContactNotifications(peer, n.copy(reactions = v)) })
                        add(switchRow("Calls", null, n.calls) { v -> actions.setContactNotifications(peer, n.copy(calls = v)) })
                    }
                },
            )
        }
        search(next)
        media(next, peer)
        links(next, peer)
        body.add(
            section("Danger") {
                add(actionRow("Clear history", "Every message in this chat", button = "Clear", isDanger = true) { actions.clearHistory(peer) })
            },
        )
    }

    private fun search(state: AppState) {
        val input = el("input", "search") {
            setAttribute("type", "search")
            setAttribute("placeholder", "Search in this chat")
            setAttribute("aria-label", "Search in this chat")
        } as HTMLInputElement
        input.value = ""
        input.on("change") { actions.searchChat(input.value) }
        val wrap = div("search-wrap").add(svg(Icon.SEARCH, 16), input)
        val results = state.chatSearchResults
        val rows = div("rows")
        when {
            results == null -> rows.add(noteRow("Type and press Enter to search this chat on the phone."))
            results.isEmpty() -> rows.add(noteRow("Nothing found."))
            else -> for (m in results.take(30)) {
                rows.add(
                    div("row row-info").add(
                        div("row-text").add(
                            div("row-label", m.body.take(140).ifEmpty { m.att?.name ?: "Attachment" }),
                            div("row-sub", "${Format.listTime(m.ts)} ${Format.clock(m.ts)}"),
                        ),
                    ),
                )
            }
        }
        body.add(div("section").add(div("section-title", "Search"), wrap, rows))
    }

    private fun media(state: AppState, peer: String) {
        val items = if (state.chatMediaPeer == peer) state.chatMedia else emptyList()
        val grid = div("media-grid")
        if (items.isEmpty()) {
            grid.add(noteRow("Nothing shared yet."))
        } else {
            for (m in items.take(24)) {
                val tile = el("button", "media-tile") {
                    setAttribute("type", "button")
                    setAttribute("aria-label", m.att?.name ?: "Attachment")
                }
                if (m.kind == DexMessageKind.IMAGE && m.att?.hasFile == true) {
                    val img = el("img") {
                        setAttribute("src", Api.attachmentUrl(m.id))
                        setAttribute("alt", m.att.name)
                        setAttribute("loading", "lazy")
                    }
                    tile.add(img)
                } else {
                    tile.add(svg(if (m.kind == DexMessageKind.VIDEO) Icon.VIDEO else Icon.FILE, 20))
                }
                tile.on("click") { actions.openLightbox(m.id) }
                grid.add(tile)
            }
        }
        body.add(div("section").add(div("section-title", "Media (${items.size})"), grid))
    }

    private fun links(state: AppState, peer: String) {
        val items = if (state.chatLinksPeer == peer) state.chatLinks else emptyList()
        val rows = div("rows")
        if (items.isEmpty()) {
            rows.add(noteRow("No links yet."))
        } else {
            for (l in items.take(30)) {
                val a = el("a", "link-row") {
                    setAttribute("href", l.url)
                    setAttribute("target", "_blank")
                    setAttribute("rel", "noopener noreferrer")
                }
                a.add(svg(Icon.LINK, 15), span("link-url", l.url), span("link-time", Format.listTime(l.ts)))
                rows.add(a)
            }
        }
        body.add(div("section").add(div("section-title", "Links (${items.size})"), rows))
    }
}

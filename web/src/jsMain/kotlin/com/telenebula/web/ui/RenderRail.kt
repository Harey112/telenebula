package com.telenebula.web.ui

import com.telenebula.web.state.Actions
import com.telenebula.web.state.AppState
import com.telenebula.web.state.Connection
import com.telenebula.web.state.Screen
import com.telenebula.web.state.Tab
import org.w3c.dom.HTMLElement

/**
 * The left rail: where the phone puts a bottom tab bar, a desktop window puts a column that can
 * also say who you are and whether the phone is still answering.
 */
class RailView(root: HTMLElement, private val actions: Actions) {
    private val host = div("rail hidden").also { root.appendChild(it) }
    private val buttons = HashMap<Tab, HTMLElement>()
    private val badges = HashMap<Tab, HTMLElement>()
    private val meName = div("rail-me-name")
    private val meIp = div("rail-me-ip mono")
    private val dot = span("conn-dot").also { it.setAttribute("role", "img") }
    private val connLabel = span("conn-label")
    private val scrim = div("rail-scrim hidden").also { root.appendChild(it) }

    init {
        host.add(div("rail-brand").add(svg(Icon.MONITOR, 20), span("brand-text", "Dex")))
        val nav = el("nav", "rail-nav")
        for (tab in Tab.entries) {
            val badge = span("rail-badge hidden")
            badges[tab] = badge
            val b = el("button", "rail-item") {
                setAttribute("type", "button")
                setAttribute("aria-label", tab.label)
                title = tab.label
            }
            b.add(svg(iconFor(tab), 20), span("rail-label", tab.label), badge)
            b.on("click") { actions.openTab(tab) }
            buttons[tab] = b
            nav.add(b)
        }
        host.add(nav)
        host.add(
            div("rail-foot").add(
                div("rail-me").add(meName, meIp),
                div("conn").add(dot, connLabel),
                button("icon-btn", "Log out", { actions.logout() }, Icon.LOGOUT),
            ),
        )
        scrim.on("click") { actions.toggleRail() }
    }

    private fun iconFor(tab: Tab): Icon = when (tab) {
        Tab.CHATS -> Icon.CHATS
        Tab.CONTACTS -> Icon.CONTACTS
        Tab.CALLS -> Icon.CALL
        Tab.SETTINGS -> Icon.SETTINGS
    }

    fun render(prev: AppState, next: AppState) {
        host.toggle("hidden", next.screen !is Screen.App)
        if (prev.tab != next.tab) {
            for ((tab, b) in buttons) {
                b.toggle("on", tab == next.tab)
                b.setAttribute("aria-current", if (tab == next.tab) "page" else "false")
            }
        }
        if (prev.isRailOpen != next.isRailOpen) {
            host.toggle("open", next.isRailOpen)
            scrim.toggle("hidden", !next.isRailOpen)
        }
        if (prev.chats !== next.chats) {
            val unread = next.unreadTotal
            badges[Tab.CHATS]?.let {
                it.textContent = if (unread > 99) "99+" else unread.toString()
                it.toggle("hidden", unread == 0)
            }
        }
        if (prev.me !== next.me) {
            meName.textContent = next.me?.name.orEmpty()
            meIp.textContent = next.me?.ip.orEmpty()
        }
        if (prev.connection != next.connection) {
            dot.className = "conn-dot " + next.connection.name.lowercase()
            val label = when (next.connection) {
                Connection.CONNECTED -> "Connected"
                Connection.CONNECTING -> "Connecting…"
                Connection.RECONNECTING -> "Reconnecting…"
            }
            dot.setAttribute("aria-label", label)
            connLabel.textContent = label
        }
    }
}

package com.telenebula.web.ui

import com.telenebula.web.net.EmojiGroup
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

/** The six the phone falls back to, used only until its own settings arrive. */
val DEFAULT_QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")

/**
 * The phone's whole catalogue, a group at a time. All 1898 at once is thousands of nodes for a
 * panel most of which is never scrolled to, so only the chosen group is built.
 */
fun emojiPicker(groups: List<EmojiGroup>, onPick: (String) -> Unit): HTMLElement {
    val host = div("emoji-picker")
    if (groups.isEmpty()) {
        host.add(div("empty small", "The phone has not sent its emoji yet."))
        return host
    }
    val tabs = div("emoji-tabs").also { it.setAttribute("role", "tablist") }
    val grid = div("emoji-grid").also { it.setAttribute("role", "listbox") }
    val filter = el("input", "text-input emoji-filter") {
        setAttribute("type", "search")
        setAttribute("placeholder", "Filter groups")
        // the catalogue carries no keywords, so only a group's name can be matched
        setAttribute("aria-label", "Filter emoji groups")
    } as HTMLInputElement

    var shown = 0
    val tabButtons = ArrayList<HTMLElement>()

    fun fill(index: Int) {
        shown = index
        for ((i, b) in tabButtons.withIndex()) {
            b.toggle("on", i == index)
            b.setAttribute("aria-selected", (i == index).toString())
        }
        grid.clear()
        val group = groups.getOrNull(index) ?: return
        for (e in group.emojis) {
            val b = el("button", "emoji-pick") {
                setAttribute("type", "button")
                setAttribute("aria-label", e)
                setAttribute("role", "option")
            }
            b.textContent = e
            b.on("click") { onPick(e) }
            grid.add(b)
        }
    }

    for ((i, g) in groups.withIndex()) {
        val b = el("button", "emoji-tab") {
            setAttribute("type", "button")
            setAttribute("role", "tab")
        }
        b.textContent = g.emojis.firstOrNull() ?: "•"
        b.title = g.title
        b.setAttribute("aria-label", g.title)
        b.on("click") { fill(i) }
        tabButtons.add(b)
        tabs.add(b)
    }

    filter.on("input") {
        val q = filter.value.trim().lowercase()
        var first = -1
        for ((i, g) in groups.withIndex()) {
            val isMatch = q.isEmpty() || g.title.lowercase().contains(q)
            tabButtons[i].toggle("hidden", !isMatch)
            if (isMatch && first < 0) first = i
        }
        if (first >= 0 && (q.isNotEmpty() || shown !in groups.indices)) fill(first)
    }
    // arrow keys walk the grid, so the whole panel is usable without a mouse
    grid.on("keydown") { e ->
        val k = e as? KeyboardEvent ?: return@on
        val step = when (k.key) {
            "ArrowRight" -> 1
            "ArrowLeft" -> -1
            else -> return@on
        }
        val buttons = grid.children
        val active = grid.ownerDocument?.activeElement
        var index = -1
        for (i in 0 until buttons.length) if (buttons.item(i) === active) index = i
        val next = buttons.item(index + step) as? HTMLElement ?: return@on
        k.preventDefault()
        next.focus()
    }

    host.add(div("emoji-head").add(tabs, filter), grid, div("emoji-note", "Every emoji the phone offers."))
    fill(0)
    return host
}

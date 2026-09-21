package com.telenebula.web.ui

import com.telenebula.web.state.Actions
import com.telenebula.web.state.AppState
import com.telenebula.web.state.CallFilter
import com.telenebula.web.state.Tab
import com.telenebula.web.util.Format
import com.telenebula.web.wire.DexCallLog
import com.telenebula.web.wire.DexCallOutcome
import com.telenebula.web.wire.DexDirection
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

private data class CallRow(val log: DexCallLog, val isSelected: Boolean)

/** The phone's call list, as the table a wide window can actually show. */
class CallLogsView(root: HTMLElement, private val actions: Actions) {
    private val host = div("pane pane-calls hidden").also { root.appendChild(it) }
    private val search = el("input", "search") {
        setAttribute("type", "search")
        setAttribute("placeholder", "Search calls")
        setAttribute("aria-label", "Search calls")
    } as HTMLInputElement
    private val filters = div("filters").also { it.setAttribute("role", "tablist") }
    private val filterButtons = HashMap<CallFilter, HTMLElement>()
    private val selectionBar = div("selection-bar hidden")
    private val selectionLabel = span("selection-count")
    private val body = div("table-body").also { it.setAttribute("role", "rowgroup") }
    private val empty = div("empty", "No calls yet.")
    private val rows = KeyedList<CallRow>(body, ::create, ::update)

    init {
        for (f in CallFilter.entries) {
            val b = el("button", "chip") {
                setAttribute("type", "button")
                setAttribute("role", "tab")
            }
            b.textContent = f.label
            b.on("click") { actions.setCallFilter(f) }
            filterButtons[f] = b
            filters.add(b)
        }
        selectionBar.add(
            selectionLabel,
            button("btn", "Delete selected", { actions.deleteSelectedCalls() }, Icon.TRASH, "Delete"),
            button("btn", "Clear selection", { actions.clearCallSelection() }, text = "Cancel"),
        )
        val head = div("table-head").add(
            span("col-icon"),
            span("col-peer", "Who"),
            span("col-kind", "Kind"),
            span("col-when", "When"),
            span("col-duration", "Duration"),
            span("col-actions"),
        )
        host.add(
            div("pane-heading-row").add(div("pane-heading", "Calls"), div("search-wrap").add(svg(Icon.SEARCH, 16), search)),
            filters,
            selectionBar,
            div("table").add(head, body),
            empty,
        )
        search.on("input") { actions.setCallSearch(search.value) }
    }

    fun render(prev: AppState, next: AppState) {
        host.toggle("hidden", next.tab != Tab.CALLS)
        if (next.tab != Tab.CALLS) return
        val changed = prev.callLogs !== next.callLogs ||
            prev.callFilter != next.callFilter ||
            prev.callSearch != next.callSearch ||
            prev.callSelection != next.callSelection ||
            prev.tab != next.tab
        if (!changed) return
        for ((f, b) in filterButtons) {
            b.toggle("on", f == next.callFilter)
            b.setAttribute("aria-selected", (f == next.callFilter).toString())
        }
        val query = next.callSearch.trim().lowercase()
        val list = next.callLogs
            .filter { matches(it, next.callFilter) }
            .filter { query.isEmpty() || it.label.lowercase().contains(query) || it.peer.contains(query) }
            .sortedByDescending { it.startedAt }
            .map { CallRow(it, it.id in next.callSelection) }
        rows.render(list) { it.log.id }
        empty.toggle("hidden", list.isNotEmpty())
        empty.textContent = if (next.callLogs.isEmpty()) "No calls yet." else "Nothing matches this filter."
        val n = next.callSelection.size
        selectionBar.toggle("hidden", n == 0)
        selectionLabel.textContent = if (n == 1) "1 call selected" else "$n calls selected"
    }

    private fun matches(log: DexCallLog, filter: CallFilter): Boolean = when (filter) {
        CallFilter.ALL -> true
        CallFilter.MISSED -> log.outcome == DexCallOutcome.MISSED
        CallFilter.IN -> log.dir == DexDirection.IN
        CallFilter.OUT -> log.dir == DexDirection.OUT
    }

    private fun create(row: CallRow): HTMLElement {
        val node = div("table-row").also { it.setAttribute("role", "row") }
        val check = el("input", "row-check") {
            setAttribute("type", "checkbox")
            setAttribute("aria-label", "Select this call")
        } as HTMLInputElement
        check.on("change") { actions.toggleCallSelected(row.log.id) }
        node.add(
            span("col-icon").add(check, span("call-icon")),
            span("col-peer"),
            span("col-kind"),
            span("col-when"),
            span("col-duration mono"),
            span("col-actions").add(
                button("icon-btn", "Voice call", { actions.callPeer(row.log.peer, false) }, Icon.CALL),
                button("icon-btn", "Video call", { actions.callPeer(row.log.peer, true) }, Icon.VIDEO),
                button("icon-btn", "Open chat", { actions.openChat(row.log.peer) }, Icon.CHATS),
            ),
        )
        update(node, row, row)
        return node
    }

    private fun update(node: HTMLElement, @Suppress("UNUSED_PARAMETER") previous: CallRow, row: CallRow) {
        val log = row.log
        node.toggle("selected", row.isSelected)
        (node.querySelector(".row-check") as? HTMLInputElement)?.checked = row.isSelected
        val missed = log.outcome == DexCallOutcome.MISSED
        (node.querySelector(".call-icon") as? HTMLElement)?.let {
            it.clear()
            it.className = "call-icon" + if (missed) " missed" else ""
            it.add(svg(if (missed) Icon.CALL_MISSED else if (log.dir == DexDirection.IN) Icon.CALL_IN else Icon.CALL_OUT, 16))
        }
        node.querySelector(".col-peer")?.textContent = log.label
        node.querySelector(".col-kind")?.textContent = if (log.isVideo) "Video" else "Voice"
        node.querySelector(".col-when")?.textContent = "${Format.listTime(log.startedAt)} ${Format.clock(log.startedAt)}"
        node.querySelector(".col-duration")?.textContent = log.connectedAt?.let { Format.duration(log.endedAt - it) } ?: outcomeLabel(log.outcome)
    }

    private fun outcomeLabel(outcome: DexCallOutcome): String = when (outcome) {
        DexCallOutcome.ANSWERED -> "Answered"
        DexCallOutcome.MISSED -> "Missed"
        DexCallOutcome.DECLINED -> "Declined"
        DexCallOutcome.NO_ANSWER -> "No answer"
        DexCallOutcome.UNREACHABLE -> "Unreachable"
        DexCallOutcome.CANCELLED -> "Cancelled"
        DexCallOutcome.FAILED -> "Failed"
    }
}

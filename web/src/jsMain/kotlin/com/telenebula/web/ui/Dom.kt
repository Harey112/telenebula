package com.telenebula.web.ui

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.svg.SVGElement

fun el(tag: String, cls: String? = null, text: String? = null, init: (HTMLElement.() -> Unit)? = null): HTMLElement {
    val e = document.createElement(tag) as HTMLElement
    if (cls != null) e.className = cls
    if (text != null) e.textContent = text
    init?.invoke(e)
    return e
}

fun div(cls: String? = null, text: String? = null, init: (HTMLElement.() -> Unit)? = null): HTMLElement = el("div", cls, text, init)

fun span(cls: String? = null, text: String? = null): HTMLElement = el("span", cls, text)

fun button(cls: String, label: String, onClick: (Event) -> Unit, icon: Icon? = null, text: String? = null): HTMLElement = el("button", cls) {
    setAttribute("type", "button")
    setAttribute("aria-label", label)
    title = label
    if (icon != null) appendChild(svg(icon))
    if (text != null) appendChild(span(null, text))
    addEventListener("click", { e -> onClick(e) })
}

fun HTMLElement.add(vararg children: Node?): HTMLElement {
    for (c in children) if (c != null) appendChild(c)
    return this
}

fun HTMLElement.clear(): HTMLElement {
    while (firstChild != null) firstChild?.let { removeChild(it) }
    return this
}

fun HTMLElement.on(type: String, handler: (Event) -> Unit): HTMLElement {
    addEventListener(type, { e -> handler(e) })
    return this
}

fun HTMLElement.toggle(cls: String, isOn: Boolean) {
    if (isOn) classList.add(cls) else classList.remove(cls)
}

/** Lucide-style 24x24 stroke paths. */
enum class Icon(val paths: List<String>) {
    SEND(listOf("m22 2-7 20-4-9-9-4Z", "M22 2 11 13")),
    ATTACH(listOf("m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48")),
    MIC(listOf("M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v3")),
    MIC_OFF(listOf("m2 2 20 20", "M18.89 13.23A7.12 7.12 0 0 0 19 12v-2", "M5 10v2a7 7 0 0 0 12 5", "M15 9.34V5a3 3 0 0 0-5.68-1.33", "M9 9v3a3 3 0 0 0 5.12 2.12", "M12 19v3")),
    LOCK(listOf("M7 11V7a5 5 0 0 1 10 0v4", "M5 11h14v10H5z")),
    UNLOCK(listOf("M7 11V7a5 5 0 0 1 9.9-1", "M5 11h14v10H5z")),
    CLOSE(listOf("M18 6 6 18", "m6 6 12 12")),
    BACK(listOf("m12 19-7-7 7-7", "M19 12H5")),
    SEARCH(listOf("M21 21l-4.3-4.3", "M11 3a8 8 0 1 0 0 16 8 8 0 1 0 0-16z")),
    CALL(listOf("M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.127.96.361 1.903.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0 1 22 16.92z")),
    CALL_END(listOf("M10.68 13.31a16 16 0 0 0 3.41 2.6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7 2 2 0 0 1 1.72 2v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.42 19.42 0 0 1-3.33-2.67m-2.67-3.34a19.79 19.79 0 0 1-3.07-8.63A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91", "M22 2 2 22")),
    VIDEO(listOf("m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5", "M2 8a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z")),
    VIDEO_OFF(listOf("M10.66 6H14a2 2 0 0 1 2 2v2.5l5.248-3.062A.5.5 0 0 1 22 7.87v8.196", "M16 16a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h2", "m2 2 20 20")),
    SMARTPHONE(listOf("M5 4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2z", "M12 18h.01")),
    REPLY(listOf("m9 17-5-5 5-5", "M20 18v-2a4 4 0 0 0-4-4H4")),
    SMILE(listOf("M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20z", "M8 14s1.5 2 4 2 4-2 4-2", "M9 9h.01", "M15 9h.01")),
    MORE(listOf("M12 12h.01", "M19 12h.01", "M5 12h.01")),
    DOWNLOAD(listOf("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5", "M12 15V3")),
    PLAY(listOf("m6 3 14 9-14 9V3z")),
    PAUSE(listOf("M14 4h4v16h-4z", "M6 4h4v16H6z")),
    STOP(listOf("M5 5h14v14H5z")),
    TRASH(listOf("M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")),
    CHECK(listOf("M20 6 9 17l-5-5")),
    CHECK_CHECK(listOf("M18 6 7 17l-5-5", "m22 10-7.5 7.5L13 16")),
    CLOCK(listOf("M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20z", "M12 6v6l4 2")),
    HOURGLASS(listOf("M5 22h14", "M5 2h14", "M17 22v-4.172a2 2 0 0 0-.586-1.414L12 12l-4.414 4.414A2 2 0 0 0 7 17.828V22", "M7 2v4.172a2 2 0 0 0 .586 1.414L12 12l4.414-4.414A2 2 0 0 0 17 6.172V2")),
    ARCHIVE(listOf("M2 3h20v5H2z", "M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8", "M10 12h4")),
    BELL_OFF(listOf("M10.268 21a2 2 0 0 0 3.464 0", "M17 17H4a1 1 0 0 1-.74-1.673C4.59 13.956 6 12.499 6 8a6 6 0 0 1 .258-1.742", "m2 2 20 20", "M8.668 3.01A6 6 0 0 1 18 8c0 2.687.77 4.653 1.707 6.05")),
    PIN(listOf("M12 17v5", "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z")),
    LOGOUT(listOf("M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "m16 17 5-5-5-5", "M21 12H9")),
    MONITOR(listOf("M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M8 21h8", "M12 17v4")),
    FILE(listOf("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7z", "M14 2v4a2 2 0 0 0 2 2h4")),
    IMAGE(listOf("M3 5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M9 9h.01", "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21")),
    COPY(listOf("M8 8h12v12H8z", "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")),
    PENCIL(listOf("M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z", "m15 5 4 4")),
    ARROW_DOWN(listOf("M12 5v14", "m19 12-7 7-7-7")),
}

fun svg(icon: Icon, size: Int = 20): SVGElement {
    val ns = "http://www.w3.org/2000/svg"
    val s = document.createElementNS(ns, "svg") as SVGElement
    s.setAttribute("width", size.toString())
    s.setAttribute("height", size.toString())
    s.setAttribute("viewBox", "0 0 24 24")
    s.setAttribute("fill", "none")
    s.setAttribute("stroke", "currentColor")
    s.setAttribute("stroke-width", "2")
    s.setAttribute("stroke-linecap", "round")
    s.setAttribute("stroke-linejoin", "round")
    s.setAttribute("aria-hidden", "true")
    for (d in icon.paths) {
        val p = document.createElementNS(ns, "path")
        p.setAttribute("d", d)
        s.appendChild(p)
    }
    return s
}

/**
 * Keeps one DOM node per key in the given order, creating with [create] and refreshing with
 * [update] only when the item changed (reference or equality). Nodes never move unless the order did.
 */
class KeyedList<T>(private val container: HTMLElement, private val create: (T) -> HTMLElement, private val update: (HTMLElement, T, T) -> Unit) {
    private val nodes = HashMap<String, HTMLElement>()
    private val items = HashMap<String, T>()

    fun render(list: List<T>, keyOf: (T) -> String) {
        val wanted = HashSet<String>(list.size * 2)
        var cursor: Element? = container.firstElementChild
        for (item in list) {
            val key = keyOf(item)
            wanted.add(key)
            val existing = nodes[key]
            val node = if (existing == null) {
                create(item).also {
                    it.setAttribute("data-key", key)
                    nodes[key] = it
                    items[key] = item
                }
            } else {
                val previous = items[key]
                if (previous == null || previous != item) {
                    update(existing, previous ?: item, item)
                    items[key] = item
                }
                existing
            }
            if (cursor !== node) container.insertBefore(node, cursor) else cursor = node.nextElementSibling
        }
        val it = nodes.entries.iterator()
        while (it.hasNext()) {
            val (key, node) = it.next()
            if (key !in wanted) {
                node.remove()
                items.remove(key)
                it.remove()
            }
        }
    }

    fun node(key: String): HTMLElement? = nodes[key]

    fun clear() {
        container.clear()
        nodes.clear()
        items.clear()
    }
}

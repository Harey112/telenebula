package com.telenebula.web.ui

import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement

/** One labelled group of rows, the phone's `Section` given a desktop's width. */
fun section(title: String? = null, footnote: String? = null, build: HTMLElement.() -> Unit): HTMLElement {
    val rows = div("rows")
    rows.build()
    val s = div("section")
    if (title != null) s.add(div("section-title", title))
    s.add(rows)
    if (footnote != null) s.add(div("section-note", footnote))
    return s
}

private fun rowShell(label: String, sub: String?, cls: String = ""): Pair<HTMLElement, HTMLElement> {
    val control = div("row-control")
    val row = div("row $cls").add(
        div("row-text").add(div("row-label", label), if (sub != null) div("row-sub", sub) else null),
        control,
    )
    return row to control
}

fun switchRow(label: String, sub: String? = null, checked: Boolean, isEnabled: Boolean = true, onChange: (Boolean) -> Unit): HTMLElement {
    val (row, control) = rowShell(label, sub)
    val input = el("input") {
        setAttribute("type", "checkbox")
        setAttribute("aria-label", label)
    } as HTMLInputElement
    input.checked = checked
    input.disabled = !isEnabled
    input.on("change") { onChange(input.checked) }
    control.add(el("label", "switch").add(input, span("switch-track").add(span("switch-thumb"))))
    if (!isEnabled) row.classList.add("disabled")
    return row
}

data class Choice(val key: String, val label: String)

fun selectRow(label: String, sub: String? = null, options: List<Choice>, selected: String, isEnabled: Boolean = true, onChange: (String) -> Unit): HTMLElement {
    val (row, control) = rowShell(label, sub)
    val sel = el("select", "select") { setAttribute("aria-label", label) } as HTMLSelectElement
    for (o in options) {
        val opt = el("option", text = o.label)
        opt.setAttribute("value", o.key)
        if (o.key == selected) opt.setAttribute("selected", "selected")
        sel.add(opt)
    }
    sel.value = selected
    sel.disabled = !isEnabled
    sel.on("change") { onChange(sel.value) }
    control.add(sel)
    if (!isEnabled) row.classList.add("disabled")
    return row
}

fun infoRow(label: String, value: String, isMono: Boolean = false, isWrapped: Boolean = false): HTMLElement {
    val (row, control) = rowShell(label, null, "row-info")
    val v = div("row-value" + (if (isMono) " mono" else "") + (if (isWrapped) " wrap" else ""), value.ifEmpty { "—" })
    control.add(v)
    return row
}

fun actionRow(label: String, sub: String? = null, button: String, isDanger: Boolean = false, isEnabled: Boolean = true, onClick: () -> Unit): HTMLElement {
    val (row, control) = rowShell(label, sub)
    val b = button("btn" + if (isDanger) " btn-danger" else "", button, { onClick() }, text = button)
    if (!isEnabled) b.setAttribute("disabled", "disabled")
    control.add(b)
    return row
}

fun textRow(label: String, sub: String? = null, value: String, placeholder: String = "", onCommit: (String) -> Unit): HTMLElement {
    val (row, control) = rowShell(label, sub)
    val input = el("input", "text-input") {
        setAttribute("type", "text")
        setAttribute("placeholder", placeholder)
        setAttribute("aria-label", label)
    } as HTMLInputElement
    input.value = value
    input.on("change") { onCommit(input.value) }
    control.add(input)
    return row
}

fun colorRow(label: String, sub: String? = null, value: String, onChange: (String) -> Unit): HTMLElement {
    val (row, control) = rowShell(label, sub)
    val input = el("input", "color-input") {
        setAttribute("type", "color")
        setAttribute("aria-label", label)
    } as HTMLInputElement
    input.value = if (value.startsWith("#") && value.length == 7) value else "#7FB7E6"
    input.on("change") { onChange(input.value) }
    control.add(span("mono color-hex", input.value), input)
    return row
}

/** `input type=time` gives the browser's own clock; the phone stores the two numbers. */
fun timeRow(label: String, hour: Int, minute: Int, isEnabled: Boolean = true, onChange: (Int, Int) -> Unit): HTMLElement {
    val (row, control) = rowShell(label, null)
    val input = el("input", "text-input time-input") {
        setAttribute("type", "time")
        setAttribute("aria-label", label)
    } as HTMLInputElement
    fun two(n: Int) = if (n < 10) "0$n" else n.toString()
    input.value = "${two(hour)}:${two(minute)}"
    input.disabled = !isEnabled
    input.on("change") {
        val parts = input.value.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()
        val m = parts.getOrNull(1)?.toIntOrNull()
        if (h != null && m != null) onChange(h.coerceIn(0, 23), m.coerceIn(0, 59))
    }
    control.add(input)
    if (!isEnabled) row.classList.add("disabled")
    return row
}

fun noteRow(text: String): HTMLElement = div("row row-note").add(div("row-sub", text))

fun textArea(label: String, value: String, placeholder: String = "", rows: Int = 3): HTMLTextAreaElement {
    val t = el("textarea", "text-input area") {
        setAttribute("placeholder", placeholder)
        setAttribute("aria-label", label)
        setAttribute("rows", rows.toString())
    } as HTMLTextAreaElement
    t.value = value
    return t
}

fun field(label: String, control: HTMLElement): HTMLElement = div("field").add(div("field-label", label), control)

fun textField(label: String, value: String, placeholder: String = "", type: String = "text"): Pair<HTMLElement, HTMLInputElement> {
    val input = el("input", "text-input") {
        setAttribute("type", type)
        setAttribute("placeholder", placeholder)
        setAttribute("aria-label", label)
    } as HTMLInputElement
    input.value = value
    return field(label, input) to input
}

/** A three-way "follow the phone / on / off", which is how a per-chat override reads. */
fun triStateRow(label: String, sub: String? = null, value: Boolean?, onChange: (Boolean?) -> Unit): HTMLElement =
    selectRow(
        label,
        sub,
        listOf(Choice("global", "Follow global"), Choice("on", "On"), Choice("off", "Off")),
        when (value) {
            null -> "global"
            true -> "on"
            false -> "off"
        },
    ) { key ->
        onChange(
            when (key) {
                "on" -> true
                "off" -> false
                else -> null
            },
        )
    }

fun statGrid(vararg pairs: Pair<String, String>): HTMLElement {
    val g = div("stat-grid")
    for ((k, v) in pairs) g.add(div("stat").add(div("stat-value", v), div("stat-key", k)))
    return g
}

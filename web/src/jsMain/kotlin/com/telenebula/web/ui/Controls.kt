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

/** Where a setting actually takes effect, said on the row rather than left to be guessed. */
/** Where a setting takes effect. A core setting is the same in every profile; only a profile setting differs. */
enum class Where(val label: String, val cls: String) {
    PHONE("Phone only", ""),
    BROWSER("Dex only", "browser"),
    BOTH("Same everywhere", ""),
    PER_PROFILE("Per profile", "profile"),
}

// --- the controls themselves, so a row and a table cell never build one two different ways ------

fun switchControl(ariaLabel: String, checked: Boolean, isEnabled: Boolean = true, onChange: (Boolean) -> Unit): HTMLElement {
    val input = el("input") {
        setAttribute("type", "checkbox")
        setAttribute("aria-label", ariaLabel)
    } as HTMLInputElement
    input.checked = checked
    input.disabled = !isEnabled
    input.on("change") { onChange(input.checked) }
    return el("label", "switch").add(input, span("switch-track").add(span("switch-thumb")))
}

fun selectControl(ariaLabel: String, options: List<Choice>, selected: String, isEnabled: Boolean = true, onChange: (String) -> Unit): HTMLElement {
    val sel = el("select", "select") { setAttribute("aria-label", ariaLabel) } as HTMLSelectElement
    for (o in options) {
        val opt = el("option", text = o.label)
        opt.setAttribute("value", o.key)
        if (o.key == selected) opt.setAttribute("selected", "selected")
        sel.add(opt)
    }
    sel.value = selected
    sel.disabled = !isEnabled
    sel.on("change") { onChange(sel.value) }
    return sel
}

fun colorControl(ariaLabel: String, value: String, isEnabled: Boolean = true, onChange: (String) -> Unit): HTMLElement {
    val input = el("input", "color-input") {
        setAttribute("type", "color")
        setAttribute("aria-label", ariaLabel)
    } as HTMLInputElement
    input.value = if (value.startsWith("#") && value.length == 7) value else "#7FB7E6"
    input.disabled = !isEnabled
    input.on("change") { onChange(input.value) }
    return div("cell-pair").add(span("mono color-hex", input.value), input)
}

private fun rowShell(label: String, sub: String?, cls: String = "", where: Where? = null): Pair<HTMLElement, HTMLElement> {
    val control = div("row-control")
    val labelRow = div("row-label", label)
    if (where != null) labelRow.add(span("row-scope ${where.cls}", where.label))
    val row = div("row $cls").add(
        div("row-text").add(labelRow, if (sub != null) div("row-sub", sub) else null),
        control,
    )
    return row to control
}

fun switchRow(label: String, sub: String? = null, checked: Boolean, isEnabled: Boolean = true, where: Where? = null, onChange: (Boolean) -> Unit): HTMLElement {
    val (row, control) = rowShell(label, sub, where = where)
    control.add(switchControl(label, checked, isEnabled, onChange))
    if (!isEnabled) row.classList.add("disabled")
    return row
}

data class Choice(val key: String, val label: String)

fun selectRow(label: String, sub: String? = null, options: List<Choice>, selected: String, isEnabled: Boolean = true, where: Where? = null, onChange: (String) -> Unit): HTMLElement {
    val (row, control) = rowShell(label, sub, where = where)
    control.add(selectControl(label, options, selected, isEnabled, onChange))
    if (!isEnabled) row.classList.add("disabled")
    return row
}

fun infoRow(label: String, value: String, isMono: Boolean = false, isWrapped: Boolean = false, where: Where? = null): HTMLElement {
    val (row, control) = rowShell(label, null, "row-info", where)
    val v = div("row-value" + (if (isMono) " mono" else "") + (if (isWrapped) " wrap" else ""), value.ifEmpty { "—" })
    control.add(v)
    return row
}

fun actionRow(label: String, sub: String? = null, button: String, isDanger: Boolean = false, isEnabled: Boolean = true, where: Where? = null, onClick: () -> Unit): HTMLElement {
    val (row, control) = rowShell(label, sub, where = where)
    val b = button("btn" + if (isDanger) " btn-danger" else "", button, { onClick() }, text = button)
    if (!isEnabled) b.setAttribute("disabled", "disabled")
    control.add(b)
    return row
}

fun textRow(label: String, sub: String? = null, value: String, placeholder: String = "", where: Where? = null, onCommit: (String) -> Unit): HTMLElement {
    val (row, control) = rowShell(label, sub, where = where)
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

fun colorRow(label: String, sub: String? = null, value: String, where: Where? = null, onChange: (String) -> Unit): HTMLElement {
    val (row, control) = rowShell(label, sub, where = where)
    control.add(colorControl(label, value, onChange = onChange))
    return row
}

/** `input type=time` gives the browser's own clock; the phone stores the two numbers. */
fun timeRow(label: String, hour: Int, minute: Int, isEnabled: Boolean = true, where: Where? = null, onChange: (Int, Int) -> Unit): HTMLElement {
    val (row, control) = rowShell(label, null, where = where)
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
fun triStateRow(label: String, sub: String? = null, value: Boolean?, where: Where? = null, onChange: (Boolean?) -> Unit): HTMLElement =
    selectRow(
        label,
        sub,
        listOf(Choice("global", "Follow global"), Choice("on", "On"), Choice("off", "Off")),
        when (value) {
            null -> "global"
            true -> "on"
            false -> "off"
        },
        where = where,
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

// --- what this browser does instead, shown under the setting it belongs to ---------------------

/** The key the browser's own control uses for "whatever the app is set to". */
const val FOLLOW_APP = ""

/** A setting and, beneath it, the browser's own value for it, as one group with one border. */
fun withSurface(parent: HTMLElement, control: HTMLElement): HTMLElement =
    div("row-group").add(
        parent,
        div("row-surface").add(div("row-surface-label", "Dex profile"), div("row-control").add(control)),
    )

/**
 * The browser's own control. "Follow app" comes first and names what it resolves to, so the value
 * in force is readable without switching away from it; null means the browser sets nothing itself.
 */
fun followSelect(
    setting: String,
    inherited: String,
    options: List<Choice>,
    selected: String?,
    isEnabled: Boolean = true,
    onChange: (String?) -> Unit,
): HTMLElement {
    val all = listOf(Choice(FOLLOW_APP, "Follow app ($inherited)")) + options
    return selectControl("$setting, this browser", all, selected ?: FOLLOW_APP, isEnabled) { key ->
        onChange(key.takeIf { it != FOLLOW_APP })
    }
}

/** The same, for a setting that is simply on or off. */
fun followSwitch(setting: String, inherited: Boolean, selected: Boolean?, isEnabled: Boolean = true, onChange: (Boolean?) -> Unit): HTMLElement =
    followSelect(
        setting,
        if (inherited) "On" else "Off",
        listOf(Choice("on", "On"), Choice("off", "Off")),
        selected?.let { if (it) "on" else "off" },
        isEnabled,
    ) { key -> onChange(key?.let { it == "on" }) }

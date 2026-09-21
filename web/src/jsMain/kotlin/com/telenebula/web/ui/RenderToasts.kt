package com.telenebula.web.ui

import com.telenebula.web.state.Actions
import com.telenebula.web.state.AppState
import com.telenebula.web.state.Toast
import com.telenebula.web.wire.DexNoticeLevel
import org.w3c.dom.HTMLElement

class ToastsView(root: HTMLElement, private val actions: Actions) {
    private val host = div("toasts").also { it.setAttribute("role", "status"); it.setAttribute("aria-live", "polite"); root.appendChild(it) }
    private val list = KeyedList<Toast>(host, ::create) { node, _, t -> node.className = classOf(t); node.querySelector(".toast-text")?.textContent = t.message }

    fun render(prev: AppState, next: AppState) {
        if (prev.toasts === next.toasts) return
        list.render(next.toasts) { it.id.toString() }
    }

    private fun classOf(t: Toast) = "toast " + when (t.level) {
        DexNoticeLevel.INFO -> "toast-info"
        DexNoticeLevel.WARNING -> "toast-warning"
        DexNoticeLevel.ERROR -> "toast-error"
    }

    private fun create(t: Toast): HTMLElement = div(classOf(t)).add(
        span("toast-text", t.message),
        button("icon-btn toast-close", "Dismiss", { actions.dismissToast(t.id) }, Icon.CLOSE),
    )
}

class PromptView(root: HTMLElement, private val actions: Actions) {
    private val host = div("modal-scrim hidden").also { root.appendChild(it) }
    private val text = div("modal-text")
    private val confirm = button("btn btn-primary", "Confirm", { actions.confirmPrompt() }, text = "OK")
    private val cancel = button("btn", "Cancel", { actions.dismissPrompt() }, text = "Cancel")

    init {
        host.add(div("modal").add(text, div("modal-actions").add(cancel, confirm)))
        host.on("click") { e -> if (e.target === host) actions.dismissPrompt() }
    }

    fun render(prev: AppState, next: AppState) {
        if (prev.prompt === next.prompt) return
        val p = next.prompt
        host.toggle("hidden", p == null)
        if (p != null) {
            text.textContent = p.message
            confirm.querySelector("span")?.textContent = p.confirmLabel
            confirm.setAttribute("aria-label", p.confirmLabel)
        }
    }
}

class LightboxView(root: HTMLElement, private val actions: Actions) {
    private val host = div("lightbox hidden").also { root.appendChild(it) }
    private val img = el("img", "lightbox-img") { setAttribute("alt", "Photo") }
    private val close = button("icon-btn lightbox-close", "Close", { actions.openLightbox(null) }, Icon.CLOSE)

    init {
        host.add(img, close)
        host.on("click") { e -> if (e.target === host || e.target === img) actions.openLightbox(null) }
    }

    fun render(prev: AppState, next: AppState, urlOf: (String) -> String) {
        if (prev.lightbox == next.lightbox) return
        val id = next.lightbox
        host.toggle("hidden", id == null)
        img.setAttribute("src", if (id == null) "" else urlOf(id))
    }
}

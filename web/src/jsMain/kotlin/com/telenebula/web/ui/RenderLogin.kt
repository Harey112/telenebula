package com.telenebula.web.ui

import com.telenebula.web.state.Actions
import com.telenebula.web.state.AppState
import com.telenebula.web.state.Screen
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

class LoginView(root: HTMLElement, private val actions: Actions) {
    private val host = div("login hidden").also { root.appendChild(it) }
    private val username = el("input", "field") { setAttribute("type", "text"); setAttribute("autocomplete", "username"); setAttribute("placeholder", "Username"); setAttribute("aria-label", "Username") } as HTMLInputElement
    private val password = el("input", "field") { setAttribute("type", "password"); setAttribute("autocomplete", "current-password"); setAttribute("placeholder", "Password"); setAttribute("aria-label", "Password") } as HTMLInputElement
    private val error = div("login-error").also { it.setAttribute("role", "alert") }
    private val submit = button("btn btn-primary btn-wide", "Log in", { submit() }, text = "Log in")

    init {
        val card = div("login-card").add(
            div("login-logo").add(svg(Icon.MONITOR, 28)),
            div("login-title", "TeleNebula Dex"),
            div("login-sub", "Sign in with the username and password set on your phone."),
            username,
            password,
            error,
            submit,
        )
        host.appendChild(card)
        val onKey = { e: org.w3c.dom.events.Event -> if ((e as? KeyboardEvent)?.key == "Enter") submit() }
        username.addEventListener("keydown", onKey)
        password.addEventListener("keydown", onKey)
    }

    private fun submit() {
        actions.login(username.value.trim(), password.value)
    }

    fun render(prev: AppState, next: AppState) {
        val screen = next.screen
        val isLogin = screen is Screen.Login
        host.toggle("hidden", !isLogin)
        if (!isLogin) {
            if (prev.screen is Screen.Login) password.value = ""
            return
        }
        if (prev.screen != screen) {
            error.textContent = screen.error.orEmpty()
            error.toggle("hidden", screen.error == null)
            submit.toggle("busy", screen.isBusy)
            (submit as? org.w3c.dom.HTMLButtonElement)?.disabled = screen.isBusy
            if (prev.screen !is Screen.Login) username.focus()
        }
    }
}

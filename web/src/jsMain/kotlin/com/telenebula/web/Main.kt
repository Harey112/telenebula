package com.telenebula.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

fun main() {
    window.addEventListener("DOMContentLoaded", { start() })
    if (document.readyState.toString() != "loading") start()
}

private var app: App? = null

private fun start() {
    if (app != null) return
    val root = document.getElementById("app") as? HTMLElement ?: return
    app = App(root).also { it.boot() }
}

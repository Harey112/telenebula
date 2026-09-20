package com.telenebula.app.ui.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which select row on a screen has its menu open; only one ever is, so one key says it. */
class OpenMenu {
    private val mutable = MutableStateFlow<String?>(null)
    val key: StateFlow<String?> = mutable.asStateFlow()

    fun open(key: String) {
        mutable.value = key
    }

    fun close() {
        mutable.value = null
    }
}

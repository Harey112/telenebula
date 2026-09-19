package com.telenebula.app.ui.shared

import com.telenebula.app.ui.fragments.SelectOption
import com.telenebula.core.model.CoverRevealGate

val CoverRevealGate.key: String get() = name.lowercase()

/** The reveal gates as a settings row shows them, in the order they ask more of the reader. */
object CoverGates {
    /** the per-chat answer that follows the global setting; never one of the gates themselves */
    const val DEFAULT_KEY = "default"

    fun of(key: String): CoverRevealGate? = CoverRevealGate.entries.firstOrNull { it.key == key }

    fun keyOf(gate: CoverRevealGate?): String = gate?.key ?: DEFAULT_KEY

    /** A phone with no screen lock is never offered the system prompt, as the app-lock switch is not either. */
    fun options(canUseDeviceAuth: Boolean, withDefault: Boolean): List<SelectOption> = buildList {
        if (withDefault) add(SelectOption(DEFAULT_KEY, "Default"))
        add(SelectOption(CoverRevealGate.TAP.key, "Just tap"))
        add(SelectOption(CoverRevealGate.ASK.key, "Ask first"))
        add(SelectOption(CoverRevealGate.CODE.key, "Code"))
        if (canUseDeviceAuth) add(SelectOption(CoverRevealGate.DEVICE.key, "Android lock"))
    }
}

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

    /** A phone with no screen lock can never answer the system prompt, so asking is the nearest thing. */
    fun effective(gate: CoverRevealGate?, fallback: CoverRevealGate, canUseDeviceAuth: Boolean): CoverRevealGate {
        val chosen = gate ?: fallback
        return if (chosen == CoverRevealGate.DEVICE && !canUseDeviceAuth) CoverRevealGate.ASK else chosen
    }

    private fun labelOf(gate: CoverRevealGate): String = when (gate) {
        CoverRevealGate.TAP -> "Just tap"
        CoverRevealGate.ASK -> "Ask first"
        CoverRevealGate.CODE -> "Code"
        CoverRevealGate.DEVICE -> "Android lock"
    }

    /** A phone with no screen lock is never offered the system prompt, as the app-lock switch is not either. */
    fun options(canUseDeviceAuth: Boolean, withDefault: Boolean): List<SelectOption> = buildList {
        if (withDefault) add(SelectOption(DEFAULT_KEY, "Default"))
        for (gate in CoverRevealGate.entries) {
            if (gate == CoverRevealGate.DEVICE && !canUseDeviceAuth) continue
            add(SelectOption(gate.key, labelOf(gate)))
        }
    }
}

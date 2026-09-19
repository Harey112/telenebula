package com.telenebula.app.ui.shared

import com.telenebula.core.model.CoverRevealGate

val CoverRevealGate.key: String get() = name.lowercase()

/** The reveal gates as a settings row shows them, in the order they ask more of the reader. */
object CoverGates {
    /** the per-chat answer that follows the global setting; never one of the gates themselves */
    const val DEFAULT_KEY = "default"
    const val DEFAULT_LABEL = "Default"

    fun of(key: String): CoverRevealGate? = CoverRevealGate.entries.firstOrNull { it.key == key }

    fun keyOf(gate: CoverRevealGate?): String = gate?.key ?: DEFAULT_KEY

    fun labelOf(gate: CoverRevealGate?): String = when (gate) {
        CoverRevealGate.TAP -> "Just tap"
        CoverRevealGate.ASK -> "Ask first"
        CoverRevealGate.CODE -> "Code"
        CoverRevealGate.DEVICE -> "Android lock"
        null -> DEFAULT_LABEL
    }

    /** A phone with no screen lock is never offered the system prompt, as the app-lock switch is not either. */
    fun gates(canUseDeviceAuth: Boolean): List<CoverRevealGate> =
        CoverRevealGate.entries.filterNot { it == CoverRevealGate.DEVICE && !canUseDeviceAuth }
}

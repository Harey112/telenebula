package com.telenebula.vpn

/** Nebula never logs a key on purpose, but its log is shown on screen, so a PEM block is redacted regardless. */
internal object LogRedaction {
    private val pemBlock = Regex("-----BEGIN [^-\\n]+-----[\\s\\S]*?-----END [^-\\n]+-----")

    fun redact(text: String): String = pemBlock.replace(text, "[redacted]")
}

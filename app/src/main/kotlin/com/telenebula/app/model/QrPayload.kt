package com.telenebula.app.model

import kotlinx.serialization.Serializable

/** What the profile QR encodes and the scanner on "New contact" expects. */
@Serializable
data class QrPayload(val app: String = APP, val name: String, val ip: String) {
    companion object {
        const val APP = "telenebula"
    }
}

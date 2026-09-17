package com.telenebula.app.platform

import com.telenebula.app.model.QrPayload
import com.telenebula.core.CoreJson

/** The profile QR code is a boundary: JSON leaves the process through the camera of another phone. */
object QrPayloads {
    fun encode(name: String, ip: String): String = CoreJson.encodeToString(QrPayload.serializer(), QrPayload(name = name, ip = ip))

    /** A scanned code: our own JSON, or a bare overlay address typed into some other tool. */
    fun decode(text: String): QrPayload? =
        runCatching { CoreJson.decodeFromString(QrPayload.serializer(), text) }.getOrNull()
            ?: if (ContactLabels.isOverlayIp(text)) QrPayload(name = "", ip = text.trim()) else null
}

package com.telenebula.core.engine

/** A peer's attachment name and type as this device stores them; whatever the envelope said, the row holds something inert. */
object AttachmentMeta {
    const val DEFAULT_MIME = "application/octet-stream"
    const val DEFAULT_NAME = "file"

    private val UNSAFE_NAME = Regex("[\\u0000-\\u001F\\u007F-\\u009F\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF/\\\\]")
    private val TYPE = Regex("[a-z0-9][a-z0-9!#$&^_.+-]{0,62}/[a-z0-9][a-z0-9!#$&^_.+-]{0,62}")

    /** Control, direction-override and path characters become `_`; the result is never empty. */
    fun name(raw: String): String {
        val clean = raw.replace(UNSAFE_NAME, "_").trim().trim('.').take(Limits.MAX_ATTACHMENT_NAME_CHARS)
        return clean.ifBlank { DEFAULT_NAME }
    }

    /** `type/subtype` lower-cased without parameters, or [DEFAULT_MIME] for anything that is not one. */
    fun mime(raw: String): String {
        val type = raw.substringBefore(';').trim().lowercase()
        return if (type.length <= Limits.MAX_ATTACHMENT_MIME_CHARS && TYPE.matches(type)) type else DEFAULT_MIME
    }
}

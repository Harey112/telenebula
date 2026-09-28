package com.telenebula.dex.http

/** Only passive media whose first bytes agree with its declared type is rendered in place; nothing stored can run as a page on the Dex origin. */
object MediaTypes {
    const val DOWNLOAD = "application/octet-stream"

    /** Bytes of the file's head [inlineType] needs to see. */
    const val SNIFF_BYTES = 16

    private val FTYP_BOXES = setOf("ftyp", "moov", "mdat", "wide", "free", "skip")

    /** The canonical type to serve inline, or null when the file is only ever a download. */
    fun inlineType(declared: String, head: ByteArray): String? {
        val type = normalise(declared) ?: return null
        val matches: Boolean = when (type) {
            "image/jpeg" -> head.startsWith(0xFF, 0xD8, 0xFF)
            "image/png" -> head.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            "image/gif" -> head.ascii(0, 6) == "GIF87a" || head.ascii(0, 6) == "GIF89a"
            "image/webp" -> head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WEBP"
            "image/bmp" -> head.ascii(0, 2) == "BM"
            "image/avif", "image/heic", "image/heif" -> head.ascii(4, 4) == "ftyp"
            "audio/mpeg" -> head.ascii(0, 3) == "ID3" || head.isMpegFrame()
            "audio/aac" -> head.isMpegFrame()
            "audio/ogg", "audio/opus", "video/ogg" -> head.ascii(0, 4) == "OggS"
            "audio/webm", "video/webm", "video/x-matroska" -> head.startsWith(0x1A, 0x45, 0xDF, 0xA3)
            "audio/wav", "audio/wave", "audio/x-wav" -> head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WAVE"
            "audio/flac" -> head.ascii(0, 4) == "fLaC"
            "audio/amr" -> head.ascii(0, 5) == "#!AMR"
            "audio/mp4", "audio/x-m4a", "audio/3gpp", "video/mp4", "video/quicktime", "video/3gpp" -> head.ascii(4, 4) in FTYP_BOXES
            else -> false
        }
        return type.takeIf { matches }
    }

    /** `type/subtype` lower-cased with its parameters dropped, or null for anything that is not one. */
    fun normalise(declared: String): String? {
        val type = declared.substringBefore(';').trim().lowercase()
        return type.takeIf { TYPE.matches(it) }
    }

    private val TYPE = Regex("[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,99}")

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        size >= bytes.size && bytes.indices.all { this[it].toInt() and 0xFF == bytes[it] }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        if (size < offset + length) "" else String(this, offset, length, Charsets.ISO_8859_1)

    private fun ByteArray.isMpegFrame(): Boolean = size >= 2 && this[0].toInt() and 0xFF == 0xFF && this[1].toInt() and 0xE0 == 0xE0
}

package com.telenebula.app.model

import android.net.Uri

/** A text file picked from the system picker, read fully into memory (certs, keys). */
data class PickedFile(val name: String, val content: String)

/** A picked file of any size, referenced by uri only (backups). */
data class PickedFileRef(val name: String, val uri: Uri)

/** An attachment picked from the system picker; only its uri travels onward. */
data class PickedAttachment(val name: String, val mime: String, val size: Long, val uri: Uri, val width: Long? = null, val height: Long? = null)

sealed interface PickResult {
    data class Picked(val file: PickedAttachment) : PickResult
    data class Rejected(val message: String) : PickResult
    data object Cancelled : PickResult
}

enum class AttachmentKind { MEDIA, DOCUMENT }

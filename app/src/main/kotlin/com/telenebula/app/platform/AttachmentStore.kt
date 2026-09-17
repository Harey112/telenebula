package com.telenebula.app.platform

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.telenebula.app.model.AttachmentKind
import com.telenebula.app.model.PickResult
import com.telenebula.app.model.PickedAttachment
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Attachment files on disk and picked-content metadata. Files travel as paths to the core;
 * nothing here loads a whole attachment into memory except [readText] for certificate PEMs.
 */
class AttachmentStore(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    val attachmentsDir: File = File(app.filesDir, "attachments")


    fun cacheFile(name: String): File = File(app.cacheDir, name)

    suspend fun readText(uri: Uri): String = withContext(io) {
        resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IOException("Could not open the picked file")
    }

    suspend fun displayName(uri: Uri): String = withContext(io) { describe(uri).name }

    /** Validates a picked attachment against the kind the user asked for. There is no size cap. */
    suspend fun inspectPicked(uri: Uri, kind: AttachmentKind): PickResult = withContext(io) {
        val meta = describe(uri)
        val mime = resolver.getType(uri) ?: DEFAULT_MIME
        if (kind == AttachmentKind.MEDIA && !mime.startsWith("image/") && !mime.startsWith("video/")) {
            return@withContext PickResult.Rejected("Pick a photo or a video here — use Files for everything else.")
        }
        val (width, height) = pixelSize(uri, mime)
        PickResult.Picked(PickedAttachment(meta.name, mime, meta.size, uri, width, height))
    }

    /** Copies picked content into permanent attachment storage; returns the stored path. */
    suspend fun storeCopy(source: Uri, id: String, name: String): File = withContext(io) {
        attachmentsDir.mkdirs()
        val dest = File(attachmentsDir, "$id-${sanitize(name)}")
        resolver.openInputStream(source)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("Could not read the picked file")
        dest
    }

    /** Copies picked content into the cache under its display name (backups the core reads by path). */
    suspend fun copyToCache(source: Uri): File = withContext(io) {
        val dest = File(app.cacheDir, sanitize(describe(source).name))
        resolver.openInputStream(source)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("Could not read the picked file")
        dest
    }

    /** Saves a received image or video into the system gallery under Pictures|Movies/TeleNebula. */
    suspend fun saveToGallery(file: File, mime: String): Boolean = withContext(io) {
        val isVideo = mime.startsWith("video/")
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/TeleNebula" else "Pictures/TeleNebula")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val item = resolver.insert(collection, values) ?: return@withContext false
        runCatching {
            resolver.openOutputStream(item)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("gallery refused the file")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(item, values, null, null)
            true
        }.getOrElse {
            resolver.delete(item, null, null)
            false
        }
    }

    private class Meta(val name: String, val size: Long)

    private fun describe(uri: Uri): Meta {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                return Meta(name?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME, size)
            }
        }
        return Meta(uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME, 0L)
    }

    /**
     * Pixel size of picked media, or nulls for anything else and for media that will not report
     * it. Images are measured from their header alone, without decoding the bitmap. Video is asked
     * for its rotation too, since a portrait clip is stored landscape with a quarter turn applied.
     *
     * Best effort by design: a file we cannot measure still sends, the bubble just falls back to a
     * default shape, so every failure here is swallowed rather than blocking the attachment.
     */
    private fun pixelSize(uri: Uri, mime: String): Pair<Long?, Long?> = try {
        when {
            mime.startsWith("image/") -> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                options.outWidth.positiveOrNull() to options.outHeight.positiveOrNull()
            }
            // AutoCloseable only arrived in API 29 and this app supports 26, so release by hand
            mime.startsWith("video/") -> {
                val probe = MediaMetadataRetriever()
                try {
                    probe.setDataSource(app, uri)
                    val width = probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val height = probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    val isQuarterTurned =
                        probe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.let { it % 180 != 0 } == true
                    if (isQuarterTurned) height.positiveOrNull() to width.positiveOrNull()
                    else width.positiveOrNull() to height.positiveOrNull()
                } finally {
                    probe.release()
                }
            }
            else -> null to null
        }
    } catch (e: Exception) {
        null to null
    }

    private fun Int?.positiveOrNull(): Long? = this?.takeIf { it > 0 }?.toLong()

    private fun sanitize(name: String): String {
        val clean = StringBuilder(name.length)
        for (ch in name) clean.append(if (ch.isLetterOrDigit() && ch.code < 128 || ch == '.' || ch == '_' || ch == '-') ch else '_')
        val tail = if (clean.length > MAX_NAME_CHARS) clean.substring(clean.length - MAX_NAME_CHARS) else clean.toString()
        return tail.ifEmpty { DEFAULT_NAME }
    }

    /**
     * Free space on the volume the attachments live on. The core cannot ask for this itself
     * without a crate outside its dependency budget, so the decision to accept a large file
     * is made with a number handed in from here.
     */
    fun freeBytes(): Long = try {
        attachmentsDir.mkdirs()
        StatFs(attachmentsDir.path).availableBytes
    } catch (e: Exception) {
        0L
    }

    companion object {
        private const val MAX_NAME_CHARS = 80
        private const val DEFAULT_NAME = "file"
        private const val DEFAULT_MIME = "application/octet-stream"
    }
}

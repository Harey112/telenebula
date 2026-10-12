package com.telenebula.core

import android.content.Context
import java.io.File
import java.util.Calendar
import java.util.Locale

/** On-disk layout shared with the previous builds (Expo's documentDirectory == filesDir). */
class CorePaths(filesDir: File, private val cacheDir: File) {
    constructor(context: Context) : this(context.filesDir, context.cacheDir)

    val dbPath: String = File(filesDir, "SQLite/telenebula.db").path
    val attachmentsDir: String = File(filesDir, "attachments").path
    val prefsPath: String = File(filesDir, "prefs.json").path
    val profilePath: String = File(filesDir, "profile.json").path

    /** Where a picked file is copied before the send is queued. */
    fun attachmentFile(id: String, name: String): File =
        File(attachmentsDir, "$id-${sanitizeFileName(name)}")

    fun backupFile(nowMs: Long = System.currentTimeMillis()): File =
        File(cacheDir, backupFileName(nowMs))

    /** Every backup this app left in the cache, sealed or half-written. */
    fun backupFiles(): List<File> = cacheDir.listFiles { file: File -> file.name.startsWith(BACKUP_PREFIX) }?.toList().orEmpty()

    companion object {
        private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

        /** Attachments live on disk; the database and the viewers both hold a `file://` uri. */
        fun pathToUri(path: String): String = "file://$path"

        fun uriToPath(uri: String): String = uri.removePrefix("file://")

        /** True when [file] resolves to a path under [dir]; the only test that survives `..` and symlinks. */
        fun isInside(dir: File, file: File): Boolean {
            val root = dir.canonicalPath.trimEnd(File.separatorChar) + File.separatorChar
            return file.canonicalPath.startsWith(root)
        }

        fun sanitizeFileName(name: String): String {
            val clean = name.replace(UNSAFE_NAME_CHARS, "_").takeLast(80)
            return clean.ifEmpty { "file" }
        }

        private const val BACKUP_PREFIX = "telenebula-backup-"

        /** `telenebula-backup-YYYYMMDD-HHmm.tnbackup` in local time. */
        fun backupFileName(nowMs: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = nowMs }
            return String.format(
                Locale.ROOT,
                "$BACKUP_PREFIX%04d%02d%02d-%02d%02d.tnbackup",
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE),
            )
        }
    }
}

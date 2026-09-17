package com.telenebula.core.backup

import com.telenebula.core.CoreException
import com.telenebula.core.CoreJson
import com.telenebula.core.CorePaths
import com.telenebula.core.db.Store
import com.telenebula.core.db.Wire
import com.telenebula.core.engine.Limits
import com.telenebula.core.model.MessageStatus
import com.telenebula.core.model.BackupManifest
import com.telenebula.core.model.BackupSummary
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Backup and restore. A backup carries the database, the preferences and every attachment a
 * message still references — never the private key or the profile, which stay on the device that
 * owns the identity. A restore merges: it adds what is missing and overwrites nothing.
 */
internal class BackupService(private val store: Store, private val paths: CorePaths) {
    fun create(prefsPath: String, destinationPath: String, appVersion: String): BackupSummary {
        val destination = File(destinationPath)
        val snapshot = File("$destinationPath.db")
        val manifestFile = File("$destinationPath.manifest")
        try {
            store.snapshotTo(snapshot.path)
            val (messages, contacts) = store.tableCounts()
            val manifest = BackupManifest(
                format = FORMAT,
                createdAt = System.currentTimeMillis(),
                appVersion = appVersion,
                attachmentsDir = paths.attachmentsDir,
                contacts = contacts,
                messages = messages,
            )
            manifestFile.writeText(PRETTY.encodeToString(BackupManifest.serializer(), manifest))

            val entries = ArrayList<Pair<String, File>>()
            entries += TarArchive.MANIFEST_NAME to manifestFile
            entries += TarArchive.DB_NAME to snapshot
            val prefs = File(prefsPath)
            if (prefs.isFile) entries += TarArchive.PREFS_NAME to prefs

            var attachments = 0
            for (path in store.referencedAttachmentPaths().sorted()) {
                val file = File(path)
                if (!file.isFile) continue
                entries += "${TarArchive.ATTACHMENTS_PREFIX}${file.name}" to file
                attachments += 1
            }

            val bytes = TarArchive.write(destination, entries)
            return BackupSummary(
                path = destinationPath,
                bytes = bytes,
                contacts = contacts,
                messages = messages,
                attachments = attachments,
            )
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally {
            snapshot.delete()
            manifestFile.delete()
        }
    }

    /**
     * Merges a backup into this device. The preferences file is restored only when this device has
     * none yet, and an attachment already on disk is left alone.
     */
    fun import(archivePath: String, prefsPath: String): BackupSummary {
        val attachmentsRoot = File(paths.attachmentsDir)
        attachmentsRoot.mkdirs()
        val archive = File(archivePath)
        val databaseCopy = File("$archivePath.db")
        val prefsMissing = !File(prefsPath).exists()

        var manifest: BackupManifest? = null
        var hasDatabase = false
        var attachments = 0
        try {
            TarArchive.read(archive) { name, _, reader ->
                when {
                    name == TarArchive.MANIFEST_NAME ->
                        manifest = CoreJson.decodeFromString(
                            BackupManifest.serializer(),
                            String(reader.readBytes(), Charsets.UTF_8),
                        )

                    name == TarArchive.DB_NAME -> {
                        TarArchive.extractTo(databaseCopy, reader)
                        hasDatabase = true
                    }

                    name == TarArchive.PREFS_NAME -> if (prefsMissing) TarArchive.extractTo(File(prefsPath), reader)

                    name.startsWith(TarArchive.ATTACHMENTS_PREFIX) -> {
                        val base = name.removePrefix(TarArchive.ATTACHMENTS_PREFIX)
                        // an archive entry never escapes the attachments directory
                        if (base.isEmpty() || base.contains('/') || base.contains("..")) return@read
                        val destination = File(attachmentsRoot, base)
                        if (!destination.exists()) {
                            TarArchive.extractTo(destination, reader)
                            attachments += 1
                        }
                    }
                }
            }

            val read = manifest ?: throw CoreException.internal("not a TeleNebula backup (no manifest)")
            if (read.format > FORMAT) {
                throw CoreException.internal("this backup (format ${read.format}) needs a newer app")
            }
            if (!hasDatabase) throw CoreException.internal("backup has no database")

            val (contacts, messages) = store.mergeFrom(databaseCopy.path, read.attachmentsDir, paths.attachmentsDir)
            return BackupSummary(
                path = archivePath,
                bytes = archive.length(),
                contacts = contacts,
                messages = messages,
                attachments = attachments,
            )
        } finally {
            databaseCopy.delete()
        }
    }

    class AttachmentScan(val orphans: List<File>, val partialCount: Int, val partialBytes: Long)

    /**
     * The one rule the Storage screen and the sweep share: a file no message references is an
     * orphan, unless it is the partial download of a transfer still open, which a returning peer
     * can finish. Its name is "<transferId>-<name>", which is how it is told apart.
     */
    fun scanAttachments(): AttachmentScan {
        val referenced = store.referencedAttachmentPaths()
        val inFlight = store.resumableTransferIds()
        val orphans = ArrayList<File>()
        var partialCount = 0
        var partialBytes = 0L
        for (file in File(paths.attachmentsDir).listFiles().orEmpty()) {
            if (!file.isFile || file.path in referenced) continue
            if (inFlight.any { file.name.startsWith("$it-") }) {
                partialCount += 1
                partialBytes += file.length()
                continue
            }
            orphans += file
        }
        return AttachmentScan(orphans, partialCount, partialBytes)
    }

    /** Gives up transfers nobody has touched for [Limits.TRANSFER_ABANDON_MS], then deletes every orphan; returns the bytes freed. */
    fun clearOrphanAttachments(nowMs: Long = System.currentTimeMillis()): Long {
        for (id in store.abandonedTransferIds(nowMs - Limits.TRANSFER_ABANDON_MS)) {
            store.setTransferState(id, Wire.TransferState.CANCELLED, ABANDONED)
            store.setMessageStatus(id, MessageStatus.CANCELLED)
        }
        var freed = 0L
        for (file in scanAttachments().orphans) {
            val size = file.length()
            if (file.delete()) freed += size
        }
        return freed
    }

    private companion object {
        const val FORMAT = 1
        const val ABANDONED = "abandoned"
        val PRETTY = Json { prettyPrint = true; encodeDefaults = true }
    }
}

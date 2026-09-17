package com.telenebula.core.backup

import com.telenebula.core.CoreException
import com.telenebula.core.CorePaths
import com.telenebula.core.db.JdbcSqlDb
import com.telenebula.core.db.Schema
import com.telenebula.core.db.Store
import com.telenebula.core.db.Wire
import com.telenebula.core.engine.Limits
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.MessageAttachment
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupServiceTest {
    private val scratch = File(System.getProperty("java.io.tmpdir"), "tn-backup-${System.nanoTime()}").apply { mkdirs() }
    private val stores = ArrayList<Store>()

    private class Device(val paths: CorePaths, val store: Store)

    private fun device(name: String): Device {
        val files = File(scratch, "$name/files").apply { mkdirs() }
        val cache = File(scratch, "$name/cache").apply { mkdirs() }
        val paths = CorePaths(files, cache)
        File(paths.dbPath).parentFile?.mkdirs()
        File(paths.attachmentsDir).mkdirs()
        val db = JdbcSqlDb.open(paths.dbPath)
        Schema.apply(db)
        return Device(paths, Store(db, paths.dbPath, JdbcSqlDb::open).also { stores += it })
    }

    @After
    fun tearDown() {
        stores.forEach { runCatching { it.close() } }
        scratch.deleteRecursively()
    }

    @Test
    fun `the orphan sweep spares live downloads, gives up abandoned ones and matches what the screen counts`() {
        val a = device("a")
        a.store.upsertContact("fd::2", "bob")
        val kept = File(a.paths.attachmentsDir, "m1-photo.jpg").apply { writeBytes(ByteArray(100)) }
        a.store.insertMessage(
            ChatMessage(
                "m1", "fd::2", MessageDirection.IN, "", 1, MessageStatus.RECEIVED, MessageKind.IMAGE,
                attachment = MessageAttachment("photo.jpg", "image/jpeg", 100, uri = CorePaths.pathToUri(kept.path)),
            ),
        )
        val orphan = File(a.paths.attachmentsDir, "gone-old.bin").apply { writeBytes(ByteArray(300)) }
        val partial = File(a.paths.attachmentsDir, "t1-big.mp4").apply { writeBytes(ByteArray(500)) }
        a.store.insertMessage(ChatMessage("t1", "fd::2", MessageDirection.IN, "", 2, MessageStatus.RECEIVING, MessageKind.VIDEO))
        a.store.upsertTransfer("t1", "fd::2", isIncoming = true, state = Wire.TransferState.RECEIVING, size = 5_000)
        // a download whose message was deleted for me can never resume: its partial is an orphan at once
        val deadPartial = File(a.paths.attachmentsDir, "t2-doc.pdf").apply { writeBytes(ByteArray(200)) }
        a.store.insertMessage(ChatMessage("t2", "fd::2", MessageDirection.IN, "", 3, MessageStatus.RECEIVING, MessageKind.FILE))
        a.store.upsertTransfer("t2", "fd::2", isIncoming = true, state = Wire.TransferState.RECEIVING, size = 2_000)
        a.store.deleteMessageForMe("t2")
        val service = BackupService(a.store, a.paths)

        val scan = service.scanAttachments()
        assertEquals(setOf(orphan, deadPartial), scan.orphans.toSet())
        assertEquals(1, scan.partialCount)
        assertEquals(500L, scan.partialBytes)
        assertEquals(listOf("t1"), a.store.resumableTransferIds())

        assertEquals(500L, service.clearOrphanAttachments())
        assertTrue(!deadPartial.exists())
        assertTrue(kept.exists())
        assertTrue("a download still open keeps its partial file", partial.exists())

        // a week on, nobody has touched the transfer: it is given up and the partial goes with it
        val later = System.currentTimeMillis() + Limits.TRANSFER_ABANDON_MS + 1
        assertEquals(500L, service.clearOrphanAttachments(nowMs = later))
        assertTrue(!partial.exists())
        assertEquals(MessageStatus.CANCELLED, a.store.getMessage("t1")?.status)
        assertTrue(a.store.unfinishedTransferIds().isEmpty())

        // deleting the message makes its file the next orphan
        a.store.deleteMessageForMe("m1")
        assertEquals(100L, service.clearOrphanAttachments())
        assertTrue(!kept.exists())
    }

    @Test
    fun `a backup round trips contacts, messages, attachments and the prefs of a device that had none`() {
        val a = device("a")
        a.store.upsertContact("fd::2", "bob")
        val file = File(a.paths.attachmentsDir, "m2-photo.jpg").apply { writeBytes(ByteArray(300) { 7 }) }
        a.store.insertMessage(ChatMessage("m1", "fd::2", MessageDirection.IN, "hello", 1, MessageStatus.RECEIVED, MessageKind.TEXT))
        a.store.insertMessage(
            ChatMessage(
                "m2", "fd::2", MessageDirection.OUT, "", 2, MessageStatus.DELIVERED, MessageKind.IMAGE,
                attachment = MessageAttachment("photo.jpg", "image/jpeg", 300, uri = CorePaths.pathToUri(file.path)),
            ),
        )
        File(a.paths.prefsPath).writeText("""{"themeMode":"DARK"}""")
        val archive = File(scratch, "backup.tar").path

        val summary = BackupService(a.store, a.paths).create(a.paths.prefsPath, archive, "test")
        assertEquals(1, summary.contacts)
        assertEquals(2, summary.messages)
        assertEquals(1, summary.attachments)

        val b = device("b")
        val imported = BackupService(b.store, b.paths).import(archive, b.paths.prefsPath)
        assertEquals(1, imported.contacts)
        assertEquals(2, imported.messages)
        assertEquals(1, imported.attachments)
        assertEquals("bob", b.store.getContact("fd::2")?.name)
        val restored = b.store.getMessage("m2")?.attachment?.uri ?: fail("no attachment")
        assertTrue("the path was rewritten to this device", CorePaths.uriToPath(restored.toString()).startsWith(b.paths.attachmentsDir))
        assertTrue(File(CorePaths.uriToPath(restored.toString())).isFile)
        assertEquals("""{"themeMode":"DARK"}""", File(b.paths.prefsPath).readText())

        // importing again adds nothing and overwrites nothing
        val again = BackupService(b.store, b.paths).import(archive, b.paths.prefsPath)
        assertEquals(0, again.contacts)
        assertEquals(0, again.messages)
    }

    @Test
    fun `an archive without a manifest or from a newer format is refused`() {
        val b = device("c")
        val noManifest = File(scratch, "nomanifest.tar")
        TarArchive.write(noManifest, listOf("chats.sqlite" to File(scratch, "x").apply { writeBytes(ByteArray(10)) }))
        try {
            BackupService(b.store, b.paths).import(noManifest.path, b.paths.prefsPath)
            fail("expected a refusal")
        } catch (e: CoreException) {
            assertTrue(e.message.orEmpty().contains("no manifest"))
        }

        val future = File(scratch, "future.tar")
        val manifest = File(scratch, "manifest.json").apply { writeText("""{"format":99,"createdAt":1,"appVersion":"x","attachmentsDir":"","contacts":0,"messages":0}""") }
        TarArchive.write(future, listOf(TarArchive.MANIFEST_NAME to manifest, TarArchive.DB_NAME to File(scratch, "x")))
        try {
            BackupService(b.store, b.paths).import(future.path, b.paths.prefsPath)
            fail("expected a refusal")
        } catch (e: CoreException) {
            assertTrue(e.message.orEmpty().contains("newer app"))
        }
    }
}

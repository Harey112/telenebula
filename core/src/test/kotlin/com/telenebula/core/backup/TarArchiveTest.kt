package com.telenebula.core.backup

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import com.telenebula.core.CoreException
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A backup has to be readable by any tar, and by us, years after it was written. */
class TarArchiveTest {
    private val scratch = File(System.getProperty("java.io.tmpdir"), "tn-tar-${System.nanoTime()}")

    @After
    fun tearDown() {
        scratch.deleteRecursively()
    }

    private fun sourceFile(name: String, bytes: ByteArray): File =
        File(scratch, name).also {
            it.parentFile?.mkdirs()
            it.writeBytes(bytes)
        }

    @Test
    fun `an archive round trips names, sizes and bytes`() {
        val longName = "attachments/${"a".repeat(40)}-${"b".repeat(80)}.bin"
        val files = listOf(
            "manifest.json" to """{"format":1}""".toByteArray(),
            "chats.sqlite" to ByteArray(1500) { 7 },
            longName to ByteArray(513) { 9 },
            "attachments/empty" to ByteArray(0),
        )
        val entries = files.mapIndexed { index, (name, bytes) -> name to sourceFile("src$index", bytes) }

        val archive = File(scratch, "backup.tar")
        val written = TarArchive.write(archive, entries)
        assertEquals(archive.length(), written)
        assertEquals("a tar is a whole number of 512-byte blocks", 0, (written % 512).toInt())

        val seen = ArrayList<Pair<String, ByteArray>>()
        TarArchive.read(archive) { name, size, reader ->
            val bytes = reader.readBytes()
            assertEquals(size, bytes.size.toLong())
            seen += name to bytes
        }
        assertEquals(files.map { it.first }, seen.map { it.first })
        for ((expected, actual) in files.zip(seen)) {
            assertTrue("bytes of ${expected.first}", expected.second.contentEquals(actual.second))
        }
    }

    @Test
    fun `an entry the reader ignores is skipped, not misparsed`() {
        val archive = File(scratch, "skip.tar")
        TarArchive.write(
            archive,
            listOf(
                "a" to sourceFile("a", ByteArray(700) { 1 }),
                "b" to sourceFile("b", "tail".toByteArray()),
            ),
        )
        val names = ArrayList<String>()
        TarArchive.read(archive) { name, _, _ -> names += name }
        assertEquals(listOf("a", "b"), names)
    }

    @Test
    fun `extracting writes the entry to its own file`() {
        val archive = File(scratch, "one.tar")
        TarArchive.write(archive, listOf("chats.sqlite" to sourceFile("db", ByteArray(2048) { 3 })))
        val destination = File(scratch, "out/chats.sqlite")
        TarArchive.read(archive) { _, _, reader -> TarArchive.extractTo(destination, reader) }
        assertEquals(2048L, destination.length())
    }

    private fun hostileHeader(name: String, sizeField: String, typeFlag: Char): ByteArray {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header)
        sizeField.toByteArray().copyInto(header, 124)
        header[156] = typeFlag.code.toByte()
        return header
    }

    @Test
    fun `a pax record claiming gigabytes is refused rather than allocated`() {
        val archive = File(scratch, "hostile-pax.tar").apply {
            parentFile?.mkdirs()
            writeBytes(hostileHeader("././@PaxHeader", "77777777777 ", 'x') + ByteArray(1024))
        }
        try {
            TarArchive.read(archive) { _, _, _ -> }
            fail("expected the size to be refused")
        } catch (e: CoreException) {
            assertEquals(CoreException.Kind.INTERNAL, e.kind)
        }
    }

    @Test
    fun `a corrupt size field is a protocol error, not a crash`() {
        val archive = File(scratch, "hostile-size.tar").apply {
            parentFile?.mkdirs()
            writeBytes(hostileHeader("a", "zzzzzzzzzzz ", '0') + ByteArray(1024))
        }
        try {
            TarArchive.read(archive) { _, _, _ -> }
            fail("expected the size to be refused")
        } catch (e: CoreException) {
            assertEquals(CoreException.Kind.INTERNAL, e.kind)
        }
    }
}

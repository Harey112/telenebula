package com.telenebula.core.backup

import com.telenebula.core.CoreException
import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    private val scratch = File(System.getProperty("java.io.tmpdir"), "tn-crypto-${System.nanoTime()}").apply { mkdirs() }
    private val password = "correct horse".toCharArray()

    @After
    fun tearDown() {
        scratch.deleteRecursively()
    }

    private fun roundTrip(size: Int) {
        val bytes = ByteArray(size) { (it * 31 % 251).toByte() }
        val plain = File(scratch, "plain-$size").apply { writeBytes(bytes) }
        val sealed = File(scratch, "sealed-$size")
        val opened = File(scratch, "opened-$size")
        BackupCrypto.seal(plain, sealed, password, iterations = 1_000)
        assertTrue(BackupCrypto.isSealed(sealed))
        assertFalse(BackupCrypto.isSealed(plain))
        BackupCrypto.open(sealed, opened, password)
        assertArrayEquals(bytes, opened.readBytes())
    }

    @Test
    fun `an empty, a small, an exact and a multi-segment archive survive the trip`() {
        roundTrip(0)
        roundTrip(1_000)
        roundTrip(BackupCrypto.SEGMENT_BYTES)
        roundTrip(BackupCrypto.SEGMENT_BYTES * 2 + 17)
    }

    private fun sealedOf(size: Int): File {
        val plain = File(scratch, "p").apply { writeBytes(ByteArray(size) { 1 }) }
        return File(scratch, "s").also { BackupCrypto.seal(plain, it, password, iterations = 1_000) }
    }

    @Test
    fun `a wrong password is refused`() {
        val sealed = sealedOf(5_000)
        assertThrows(CoreException::class.java) { BackupCrypto.open(sealed, File(scratch, "o"), "wrong password".toCharArray()) }
    }

    @Test
    fun `a flipped byte is refused`() {
        val sealed = sealedOf(5_000)
        RandomAccessFile(sealed, "rw").use { it.seek(sealed.length() - 5); val b = it.read(); it.seek(sealed.length() - 5); it.write(b xor 1) }
        assertThrows(CoreException::class.java) { BackupCrypto.open(sealed, File(scratch, "o"), password) }
    }

    @Test
    fun `a file cut after a whole segment is refused`() {
        val sealed = sealedOf(BackupCrypto.SEGMENT_BYTES * 2 + 10)
        val firstSegmentEnd = 10 + 16 + 4 + 8 + 1 + 4 + BackupCrypto.SEGMENT_BYTES + 16
        RandomAccessFile(sealed, "rw").use { it.setLength(firstSegmentEnd.toLong()) }
        assertThrows(CoreException::class.java) { BackupCrypto.open(sealed, File(scratch, "o"), password) }
    }

    @Test
    fun `a plain archive is not mistaken for a sealed one`() {
        val tar = File(scratch, "plain.tar").apply { writeBytes(ByteArray(1024)) }
        assertFalse(BackupCrypto.isSealed(tar))
        assertThrows(CoreException::class.java) { BackupCrypto.open(tar, File(scratch, "o"), password) }
    }
}

package com.telenebula.core.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The device's own providers: PBKDF2WithHmacSHA256 and AES/GCM, at the real iteration count. */
@RunWith(AndroidJUnit4::class)
class AndroidBackupCryptoTest {
    private val scratch = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "crypto-${System.nanoTime()}").apply { mkdirs() }

    @After
    fun tearDown() {
        scratch.deleteRecursively()
    }

    @Test
    fun aMultiSegmentBackup_sealsAndOpensOnTheDevice() {
        val bytes = ByteArray(BackupCrypto.SEGMENT_BYTES * 3 + 123) { (it % 253).toByte() }
        val plain = File(scratch, "plain").apply { writeBytes(bytes) }
        val sealed = File(scratch, "sealed")
        val opened = File(scratch, "opened")
        val started = System.nanoTime()
        BackupCrypto.seal(plain, sealed, "correct horse".toCharArray())
        BackupCrypto.open(sealed, opened, "correct horse".toCharArray())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertArrayEquals(bytes, opened.readBytes())
        assertTrue("sealing and opening took $elapsedMs ms", elapsedMs < 30_000)
        println("backup crypto round trip: $elapsedMs ms")
    }

}

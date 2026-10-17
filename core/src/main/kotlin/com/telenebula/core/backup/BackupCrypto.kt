package com.telenebula.core.backup

import com.telenebula.core.CoreException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A backup sealed under a password: PBKDF2-SHA256, then AES-256-GCM per segment, its index and last-flag authenticated. */
internal object BackupCrypto {
    private val MAGIC = "TNBACKUP1\n".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 8
    private const val TAG_BITS = 128
    const val SEGMENT_BYTES = 1 shl 20
    /** ~5 s on a low-end 2020 phone; stored per file, so raising it never breaks an older backup. */
    const val ITERATIONS = 150_000
    const val MIN_PASSWORD_CHARS = 8

    fun isSealed(file: File): Boolean = file.inputStream().use { input ->
        val head = ByteArray(MAGIC.size)
        readFully(input, head) && head.contentEquals(MAGIC)
    }

    fun seal(plain: File, sealed: File, password: CharArray, iterations: Int = ITERATIONS) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
        val header = header(salt, iterations, prefix)
        val key = derive(password, salt, iterations)
        DataOutputStream(sealed.outputStream().buffered()).use { out ->
            out.write(header)
            plain.inputStream().use { input ->
                var index = 0
                var current = ByteArray(SEGMENT_BYTES)
                var currentLength = readUpTo(input, current)
                while (true) {
                    val next = ByteArray(SEGMENT_BYTES)
                    val nextLength = if (currentLength < SEGMENT_BYTES) -1 else readUpTo(input, next)
                    val isLast = nextLength <= 0
                    val sealedSegment = cipher(Cipher.ENCRYPT_MODE, key, prefix, index, header, isLast).doFinal(current, 0, currentLength)
                    out.writeByte(if (isLast) 1 else 0)
                    out.writeInt(sealedSegment.size)
                    out.write(sealedSegment)
                    if (isLast) break
                    index++
                    current = next
                    currentLength = nextLength
                }
            }
        }
    }

    /** Throws [CoreException] for a wrong password, a damaged file or one that is not a sealed backup. */
    fun open(sealed: File, plain: File, password: CharArray) {
        DataInputStream(sealed.inputStream().buffered()).use { input ->
            val magic = ByteArray(MAGIC.size)
            if (!readFully(input, magic) || !magic.contentEquals(MAGIC)) throw CoreException.protocol("not an encrypted TeleNebula backup")
            val salt = ByteArray(SALT_BYTES).also { if (!readFully(input, it)) throw damaged() }
            val iterations = input.readInt()
            if (iterations !in 1..10_000_000) throw damaged()
            val prefix = ByteArray(NONCE_PREFIX_BYTES).also { if (!readFully(input, it)) throw damaged() }
            val header = header(salt, iterations, prefix)
            val key = derive(password, salt, iterations)
            plain.outputStream().buffered().use { out ->
                var index = 0
                while (true) {
                    val flag = try { input.readByte().toInt() } catch (_: EOFException) { throw damaged() }
                    val length = input.readInt()
                    if (length !in 0..(SEGMENT_BYTES + TAG_BITS / 8)) throw damaged()
                    val segment = ByteArray(length).also { if (!readFully(input, it)) throw damaged() }
                    val isLast = flag == 1
                    val clear = try {
                        cipher(Cipher.DECRYPT_MODE, key, prefix, index, header, isLast).doFinal(segment)
                    } catch (_: AEADBadTagException) {
                        throw CoreException.protocol("wrong password, or the backup is damaged")
                    }
                    out.write(clear)
                    if (isLast) break
                    index++
                }
            }
            if (input.read() != -1) throw damaged()
        }
    }

    private fun damaged() = CoreException.protocol("the backup is damaged or incomplete")

    private fun header(salt: ByteArray, iterations: Int, prefix: ByteArray): ByteArray =
        ByteBuffer.allocate(MAGIC.size + SALT_BYTES + 4 + NONCE_PREFIX_BYTES).put(MAGIC).put(salt).putInt(iterations).put(prefix).array()

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, index: Int, header: ByteArray, isLast: Boolean): Cipher {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
            updateAAD(byteArrayOf(if (isLast) 1 else 0))
        }
    }

    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean = readUpTo(input, buffer) == buffer.size
}

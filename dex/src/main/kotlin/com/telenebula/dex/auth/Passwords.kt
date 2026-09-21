package com.telenebula.dex.auth

import com.telenebula.dex.PasswordHash
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Passwords {
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val MAX_ITERATIONS = 10_000_000

    fun hash(password: String, iterations: Int = 120_000): PasswordHash {
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of range" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = derive(password, salt, iterations)
        return PasswordHash(PasswordHash.ALGORITHM, iterations, Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(derived))
    }

    /** False for a wrong password and for a hash this build cannot read; never throws. */
    fun verify(password: String, hash: PasswordHash): Boolean {
        if (hash.algorithm != PasswordHash.ALGORITHM) return false
        if (hash.iterations !in 1..MAX_ITERATIONS) return false
        val salt = decode(hash.saltB64) ?: return false
        val expected = decode(hash.hashB64) ?: return false
        if (salt.isEmpty() || expected.size != KEY_BITS / 8) return false
        val actual = try {
            derive(password, salt, hash.iterations)
        } catch (e: Exception) {
            return false
        }
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun decode(b64: String): ByteArray? = try {
        Base64.getDecoder().decode(b64)
    } catch (e: IllegalArgumentException) {
        null
    }
}

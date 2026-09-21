package com.telenebula.dex.auth

import com.telenebula.dex.PasswordHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordsTest {
    @Test
    fun `a hashed password verifies and a wrong one does not`() {
        val hash = Passwords.hash("correct horse", iterations = 1_000)
        assertTrue(Passwords.verify("correct horse", hash))
        assertFalse(Passwords.verify("correct horsf", hash))
        assertFalse(Passwords.verify("", hash))
        assertEquals(PasswordHash.ALGORITHM, hash.algorithm)
    }

    @Test
    fun `two hashes of the same password differ by salt`() {
        val a = Passwords.hash("same", iterations = 1_000)
        val b = Passwords.hash("same", iterations = 1_000)
        assertNotEquals(a.saltB64, b.saltB64)
        assertNotEquals(a.hashB64, b.hashB64)
    }

    @Test
    fun `a hash this build cannot read never verifies and never throws`() {
        val good = Passwords.hash("pw", iterations = 1_000)
        assertFalse(Passwords.verify("pw", PasswordHash("argon2", good.iterations, good.saltB64, good.hashB64)))
        assertFalse(Passwords.verify("pw", PasswordHash(good.algorithm, 0, good.saltB64, good.hashB64)))
        assertFalse(Passwords.verify("pw", PasswordHash(good.algorithm, good.iterations, "not base64!", good.hashB64)))
        assertFalse(Passwords.verify("pw", PasswordHash(good.algorithm, good.iterations, good.saltB64, "AAAA")))
        assertFalse(Passwords.verify("pw", PasswordHash(good.algorithm, good.iterations, "", good.hashB64)))
    }
}

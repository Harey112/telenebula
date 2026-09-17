package com.telenebula.core.db

import java.security.SecureRandom

/** 32 lowercase hex characters, the shape `lower(hex(randomblob(16)))` produced, minted without the store lock. */
internal object Ids {
    private val random = SecureRandom()
    private val digits = "0123456789abcdef".toCharArray()

    fun newId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val out = CharArray(32)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            out[2 * i] = digits[b ushr 4]
            out[2 * i + 1] = digits[b and 0x0F]
        }
        return String(out)
    }
}

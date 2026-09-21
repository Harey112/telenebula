package com.telenebula.dex.tls

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.security.auth.x500.X500Principal

/** The key the first Dex build made: SHA-256 only, which no handshake can use. */
object LegacyKey {
    fun create(alias: String) {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal("CN=TeleNebula Dex"))
            .setCertificateSerialNumber(BigInteger(64, SecureRandom()).setBit(63))
            .setCertificateNotBefore(Calendar.getInstance().time)
            .setCertificateNotAfter(Calendar.getInstance().apply { add(Calendar.YEAR, 10) }.time)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }
}

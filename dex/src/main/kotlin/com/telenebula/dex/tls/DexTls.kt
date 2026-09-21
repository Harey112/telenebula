package com.telenebula.dex.tls

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.telenebula.dex.DexException
import com.telenebula.dex.DexFailure
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.security.auth.x500.X500Principal

/** The Dex server's own certificate: an EC key the Android Keystore made and holds, self-signed. */
class DexTls(private val alias: String = DEFAULT_ALIAS) {
    private val lock = Any()

    /** SHA-256 of the DER certificate as `AB:CD:…`, what the browser's certificate viewer shows. */
    val fingerprintSha256: String
        get() = fingerprint(certificate())

    fun serverSocketFactory(): SSLServerSocketFactory {
        val keyStore = keyStore()
        ensureKey(keyStore)
        return try {
            val keyManagers = KeyManagerFactory.getInstance("PKIX").apply { init(keyStore, null) }.keyManagers
            SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }.serverSocketFactory
        } catch (e: Exception) {
            throw DexException(DexFailure.CERTIFICATE, "Dex certificate cannot be used for TLS: ${e.message}", e)
        }
    }

    /** Forgets the certificate; the next start makes a new one and every browser sees a new fingerprint. */
    fun reset() {
        try {
            synchronized(lock) { keyStore().deleteEntry(alias) }
        } catch (e: Exception) {
            throw DexException(DexFailure.CERTIFICATE, "Dex certificate could not be removed: ${e.message}", e)
        }
    }

    private fun certificate(): X509Certificate {
        val keyStore = keyStore()
        ensureKey(keyStore)
        return keyStore.getCertificate(alias) as? X509Certificate
            ?: throw DexException(DexFailure.CERTIFICATE, "Dex certificate is missing from the keystore")
    }

    private fun ensureKey(keyStore: KeyStore) {
        synchronized(lock) {
            if (keyStore.containsAlias(alias) && keyStore.getCertificate(alias) is X509Certificate) return
            try {
                val notBefore = Calendar.getInstance()
                val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, VALIDITY_YEARS) }
                val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(X500Principal("CN=TeleNebula Dex"))
                    .setCertificateSerialNumber(BigInteger(64, SecureRandom()).setBit(63))
                    .setCertificateNotBefore(notBefore.time)
                    .setCertificateNotAfter(notAfter.time)
                    .build()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply { initialize(spec) }.generateKeyPair()
            } catch (e: Exception) {
                throw DexException(DexFailure.CERTIFICATE, "Dex certificate could not be created: ${e.message}", e)
            }
        }
    }

    private fun keyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (e: Exception) {
        throw DexException(DexFailure.CERTIFICATE, "Android Keystore is unavailable: ${e.message}", e)
    }

    private fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

    companion object {
        const val DEFAULT_ALIAS = "tn.dex.tls"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val VALIDITY_YEARS = 10
    }
}

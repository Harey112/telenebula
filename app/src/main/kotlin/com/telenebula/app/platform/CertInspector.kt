package com.telenebula.app.platform

import com.telenebula.app.model.CertExpiryLevel
import com.telenebula.app.model.CertExpiryStatus
import com.telenebula.app.model.HostCertInfo
import com.telenebula.vpn.NebulaVpnController
import com.telenebula.vpn.NebulaVpnException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Reads nebula PEMs through the native bindings and words their validity for the UI. */
class CertInspector(private val vpn: NebulaVpnController) {
    /** Parses a PEM (host or CA cert). Throws [NebulaVpnException] with a readable message. */
    suspend fun inspect(pem: String): HostCertInfo {
        val entry = vpn.parseCerts(pem).firstOrNull() ?: throw NebulaVpnException("No certificate found in file")
        val cert = entry.cert
        return HostCertInfo(
            name = cert.name,
            overlayIp = cert.networks.firstOrNull()?.substringBefore('/') ?: "",
            networks = cert.networks,
            fingerprint = cert.fingerprint,
            notAfter = cert.notAfter,
            isCa = cert.isCa,
            isValid = entry.validity.valid,
            invalidReason = entry.validity.reason,
        )
    }

    suspend fun verifyCertAndKey(certPem: String, keyPem: String): Boolean = vpn.verifyCertAndKey(certPem, keyPem)

    fun looksLikeCertPem(content: String): Boolean = content.contains("NEBULA CERTIFICATE")

    fun looksLikeKeyPem(content: String): Boolean = content.contains("PRIVATE KEY")

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val RENEW_WARNING_DAYS = 30
        private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

        /** Human status of a certificate's validity window, warning a month ahead. */
        fun expiryStatus(notAfter: String, now: Long = System.currentTimeMillis()): CertExpiryStatus {
            val expiresAt = runCatching { Instant.parse(notAfter).toEpochMilli() }.getOrNull()
                ?: return CertExpiryStatus(CertExpiryLevel.WARNING, "Expiry date unknown")
            val date = Instant.ofEpochMilli(expiresAt).atZone(ZoneId.systemDefault()).format(dateFormat)
            val days = Math.floorDiv(expiresAt - now, DAY_MS)
            return when {
                days < 0 -> CertExpiryStatus(CertExpiryLevel.EXPIRED, "Expired on $date")
                days == 0L -> CertExpiryStatus(CertExpiryLevel.WARNING, "Expires today")
                days <= RENEW_WARNING_DAYS ->
                    CertExpiryStatus(CertExpiryLevel.WARNING, "Expires in $days day${if (days == 1L) "" else "s"} ($date)")
                else -> CertExpiryStatus(CertExpiryLevel.OK, "Valid until $date")
            }
        }
    }
}

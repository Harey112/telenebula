package com.telenebula.vpn.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One entry of the JSON array returned by mobile_nebula's ParseCerts. */
@Serializable
data class ParsedCertEntry(
    @SerialName("RawCert") val rawCert: String = "",
    @SerialName("Cert") val cert: CertDetails,
    @SerialName("Validity") val validity: Validity = Validity(),
) {
    @Serializable
    data class CertDetails(
        val version: Int = 0,
        val name: String = "",
        val networks: List<String> = emptyList(),
        val unsafeNetworks: List<String> = emptyList(),
        val groups: List<String> = emptyList(),
        val isCa: Boolean = false,
        val notBefore: String = "",
        val notAfter: String = "",
        val issuer: String = "",
        val curve: String = "",
        val fingerprint: String = "",
    )

    @Serializable
    data class Validity(
        @SerialName("Valid") val valid: Boolean = false,
        @SerialName("Reason") val reason: String = "",
    )
}

package com.telenebula.app.model

data class HostCertInfo(
    val name: String,
    val overlayIp: String,
    val networks: List<String>,
    val fingerprint: String,
    val notAfter: String,
    val isCa: Boolean,
    val isValid: Boolean,
    val invalidReason: String,
)

enum class CertExpiryLevel { OK, WARNING, EXPIRED }

data class CertExpiryStatus(val level: CertExpiryLevel, val text: String)

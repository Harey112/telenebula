package com.telenebula.app.ui.screens.renew

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.model.HostCertInfo
import com.telenebula.app.nav.Navigator
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.notices.reporting
import com.telenebula.app.platform.ActivityGateway
import com.telenebula.app.platform.AttachmentStore
import com.telenebula.app.platform.CertInspector
import com.telenebula.app.platform.IdentityStore
import com.telenebula.app.platform.userMessage
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.core.model.Profile
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RenewUiState(
    val currentName: String = "",
    val currentIp: String = "",
    val currentExpiry: String = "",
    val hasCert: Boolean = false,
    val certDetail: String? = null,
    val hasKey: Boolean = false,
    val hasCa: Boolean = false,
    val caDetail: String = "Only if the CA was replaced",
    /** consequences of the picked certificate the user should read before applying */
    val warnings: List<String> = emptyList(),
    val error: String = "",
    val isBusy: Boolean = false,
) {
    val canApply: Boolean get() = hasCert && hasKey && !isBusy
}

/**
 * Replaces the device's host certificate and key in place. Chats, contacts and preferences
 * stay; the runtime restarts so nebula and the core pick up the new identity.
 */
class RenewCertificateViewModel(
    private val runtime: AppRuntime,
    private val gateway: ActivityGateway,
    private val files: AttachmentStore,
    private val certs: CertInspector,
    private val identity: IdentityStore,
    private val notices: NoticeCenter,
    private val navigator: Navigator,
) : ViewModel() {
    private class Picked(
        val certPem: String? = null,
        val certInfo: HostCertInfo? = null,
        val hasKey: Boolean = false,
        val caPem: String? = null,
        val caInfo: HostCertInfo? = null,
        val error: String = "",
        val isBusy: Boolean = false,
    )

    private val picked = MutableStateFlow(Picked())

    val uiState: StateFlow<RenewUiState> = combine(runtime.profile, picked, ::buildState)
        .uiState(viewModelScope, buildState(runtime.profile.value, picked.value))

    // runtime.profile is already a live StateFlow now — seeding blank is what flashes an empty
    // "current certificate" card for a frame on every revisit.
    private fun buildState(profile: Profile?, p: Picked): RenewUiState = RenewUiState(
        currentName = profile?.certName.orEmpty(),
        currentIp = profile?.overlayIp.orEmpty(),
        currentExpiry = profile?.let { CertInspector.expiryStatus(it.certNotAfter).text }.orEmpty(),
        hasCert = p.certPem != null,
        certDetail = p.certInfo?.let { "${it.name} — ${it.overlayIp}" },
        hasKey = p.hasKey,
        hasCa = p.caPem != null,
        caDetail = p.caInfo?.let { "CA: ${it.name}" } ?: "Only if the CA was replaced",
        warnings = warnings(profile, p),
        error = p.error,
        isBusy = p.isBusy,
    )

    private fun warnings(profile: Profile?, p: Picked): List<String> {
        val info = p.certInfo ?: return emptyList()
        if (profile == null) return emptyList()
        val out = ArrayList<String>(5)
        if (info.overlayIp != profile.overlayIp) {
            out.add("New address ${info.overlayIp}. Your contacts must change your address in their contact record before they can reach you again; your chats stay on this device.")
        }
        if (info.name != profile.certName) out.add("Your username changes from @${profile.certName} to @${info.name}.")
        if (info.fingerprint == profile.certFingerprint) out.add("This is the certificate you already use.")
        if (!info.isValid && info.invalidReason.isNotEmpty()) out.add("Nebula reports: ${info.invalidReason}.")
        if (p.caPem != null) out.add("The CA changes too; every peer must trust the new CA.")
        return out
    }

    fun pickCert() = pickText { name, content ->
        notices.reporting("Host certificate rejected") {
            if (!certs.looksLikeCertPem(content)) throw IllegalArgumentException("Not a nebula certificate file")
            val info = certs.inspect(content)
            if (info.isCa) throw IllegalArgumentException("$name is a CA cert, pick the host cert")
            if (info.overlayIp.isEmpty()) throw IllegalArgumentException("Host cert has no overlay network")
            picked.update { Picked(content, info, it.hasKey, it.caPem, it.caInfo) }
        }
    }

    fun pickKey() = pickText { _, content ->
        if (!certs.looksLikeKeyPem(content)) {
            notices.addWarning("Not a private key: Pick the host.key file.")
            return@pickText
        }
        identity.stageHostKey(content)
        picked.update { Picked(it.certPem, it.certInfo, true, it.caPem, it.caInfo) }
    }

    fun pickCa() = pickText { name, content ->
        notices.reporting("CA certificate rejected") {
            if (!certs.looksLikeCertPem(content)) throw IllegalArgumentException("Not a nebula certificate file")
            val info = certs.inspect(content)
            if (!info.isCa) throw IllegalArgumentException("$name is not a CA certificate")
            picked.update { Picked(it.certPem, it.certInfo, it.hasKey, content, info) }
        }
    }

    fun apply() {
        val profile = runtime.profile.value ?: return
        val p = picked.value
        val certPem = p.certPem ?: return
        val info = p.certInfo ?: return
        if (!p.hasKey || p.isBusy) return
        picked.update { Picked(it.certPem, it.certInfo, it.hasKey, it.caPem, it.caInfo, isBusy = true) }
        viewModelScope.launch {
            try {
                notices.withLoading("Restarting the tunnel with the new identity…") {
                    val keyPem = identity.stagedHostKey() ?: throw IllegalArgumentException("The private key could not be read; pick it again")
                    if (!certs.verifyCertAndKey(certPem, keyPem)) throw IllegalArgumentException("The private key does not belong to this certificate")
                    val next = profile.copy(
                        certName = info.name,
                        overlayIp = info.overlayIp,
                        networks = info.networks,
                        certPem = certPem,
                        certFingerprint = info.fingerprint,
                        certNotAfter = info.notAfter,
                        caPem = p.caPem ?: profile.caPem,
                    )
                    runtime.stopRuntime()
                    identity.commitStagedHostKey()
                    identity.saveProfile(next)
                    runtime.startRuntime(next)
                    notices.setSuccess("Certificate renewed: ${CertInspector.expiryStatus(next.certNotAfter).text}")
                }
                navigator.pop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addError("Couldn't replace the certificate: ${e.userMessage()}")
            } finally {
                picked.update { Picked(it.certPem, it.certInfo, it.hasKey, it.caPem, it.caInfo, isBusy = false) }
            }
        }
    }

    fun goBack() = navigator.pop()

    private fun pickText(onPicked: suspend (name: String, content: String) -> Unit) {
        viewModelScope.launch {
            val uri = gateway.pickDocument() ?: return@launch
            notices.reporting("Couldn't read the file") {
                onPicked(files.displayName(uri), files.readText(uri))
            }
        }
    }
}

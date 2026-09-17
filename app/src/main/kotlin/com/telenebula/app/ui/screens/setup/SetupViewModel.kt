package com.telenebula.app.ui.screens.setup

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.model.HostCertInfo
import com.telenebula.app.nebula.NebulaDraftStore
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.notices.reporting
import com.telenebula.app.platform.ActivityGateway
import com.telenebula.app.platform.AttachmentStore
import com.telenebula.app.platform.CertInspector
import com.telenebula.app.platform.Endpoints
import com.telenebula.app.platform.IdentityStore
import com.telenebula.app.platform.userMessage
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.core.model.LighthouseEntry
import com.telenebula.core.model.NebulaAdvancedConfig
import com.telenebula.core.model.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupUiState(
    val hasCa: Boolean = false,
    val caDetail: String? = null,
    val hasCert: Boolean = false,
    val certDetail: String? = null,
    val hasKey: Boolean = false,
    val certInfo: HostCertInfo? = null,
    val lhNebulaIp: String = "",
    val lhHost: String = "",
    val lhPort: String = "",
    val error: String = "",
    val isBusy: Boolean = false,
) {
    val canCreate: Boolean
        get() = hasCa && hasCert && certInfo != null && hasKey && lhNebulaIp.isNotBlank() && Endpoints.isValid(lhHost, lhPort)
}

@Stable
interface SetupActions {
    fun pickCa()
    fun pickCert()
    fun pickKey()
    fun setLhNebulaIp(value: String)
    fun setLhHost(value: String)
    fun setLhPort(value: String)
    fun create()
}

class SetupViewModel(
    private val gateway: ActivityGateway,
    private val files: AttachmentStore,
    private val certs: CertInspector,
    private val identity: IdentityStore,
    private val runtime: AppRuntime,
    private val draftStore: NebulaDraftStore,
    private val notices: NoticeCenter,
) : ViewModel(), SetupActions {
    private val mutable = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = mutable.asStateFlow()

    private var caPem: String? = null
    private var certPem: String? = null

    init {
        // the advanced draft is shared with the lighthouse screen; setup starts from defaults
        draftStore.hydrateFrom(NebulaAdvancedConfig(), DEFAULT_MSG_PORT)
    }

    override fun pickCa() = pickText { name, content ->
        notices.reporting("CA certificate rejected") {
            if (!certs.looksLikeCertPem(content)) throw IllegalArgumentException("Not a nebula certificate file")
            val info = certs.inspect(content)
            if (!info.isCa) throw IllegalArgumentException("$name is not a CA certificate")
            caPem = content
            mutable.update { it.copy(hasCa = true, caDetail = "CA: ${info.name}", error = "") }
        }
    }

    override fun pickCert() = pickText { name, content ->
        notices.reporting("Host certificate rejected") {
            if (!certs.looksLikeCertPem(content)) throw IllegalArgumentException("Not a nebula certificate file")
            val info = certs.inspect(content)
            if (info.isCa) throw IllegalArgumentException("$name is a CA cert, pick the host cert")
            if (info.overlayIp.isEmpty()) throw IllegalArgumentException("Host cert has no overlay network")
            certPem = content
            mutable.update { it.copy(hasCert = true, certDetail = "${info.name} — ${info.overlayIp}", certInfo = info, error = "") }
        }
    }

    override fun pickKey() = pickText { _, content ->
        if (!certs.looksLikeKeyPem(content)) {
            mutable.update { it.copy(error = "Key: not a private key file") }
            return@pickText
        }
        identity.stageHostKey(content)
        mutable.update { it.copy(hasKey = true, error = "") }
    }

    override fun setLhNebulaIp(value: String) = mutable.update { it.copy(lhNebulaIp = value) }

    override fun setLhHost(value: String) = mutable.update { it.copy(lhHost = value) }

    override fun setLhPort(value: String) = mutable.update { it.copy(lhPort = value.filter(Char::isDigit)) }

    override fun create() {
        val s = mutable.value
        val ca = caPem ?: return
        val cert = certPem ?: return
        val info = s.certInfo ?: return
        if (!s.canCreate || s.isBusy) return
        mutable.update { it.copy(isBusy = true, error = "") }
        viewModelScope.launch {
            try {
                val key = identity.stagedHostKey() ?: throw IllegalStateException("The private key could not be read; pick it again")
                if (!certs.verifyCertAndKey(cert, key)) throw IllegalStateException("The private key does not belong to this certificate")
                val advanced = draftStore.commit()
                if (advanced.errors.isNotEmpty()) {
                    throw IllegalStateException("Advanced nebula settings have ${advanced.errors.size} invalid field(s); they are marked in red.")
                }
                val listenPort = advanced.config.listen.port
                val profile = Profile(
                    certName = info.name,
                    overlayIp = info.overlayIp,
                    networks = info.networks,
                    caPem = ca,
                    certPem = cert,
                    certFingerprint = info.fingerprint,
                    certNotAfter = info.notAfter,
                    lighthouses = listOf(LighthouseEntry(s.lhNebulaIp.trim(), Endpoints.join(s.lhHost, s.lhPort))),
                    listenPort = listenPort,
                    msgPort = DEFAULT_MSG_PORT,
                    nebula = advanced.config,
                )
                identity.commitStagedHostKey()
                identity.saveProfile(profile)
                runtime.startRuntime(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addError("Couldn't create the identity: ${e.userMessage()}")
            } finally {
                mutable.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun pickText(onPicked: suspend (name: String, content: String) -> Unit) {
        viewModelScope.launch {
            val uri = gateway.pickDocument() ?: return@launch
            notices.reporting("Couldn't read the file") {
                onPicked(files.displayName(uri), files.readText(uri))
            }
        }
    }

    private companion object {
        const val DEFAULT_MSG_PORT = 4433
    }
}

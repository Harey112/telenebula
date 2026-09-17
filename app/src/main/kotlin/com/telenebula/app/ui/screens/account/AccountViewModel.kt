package com.telenebula.app.ui.screens.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.BuildConfig
import com.telenebula.app.model.CertExpiryLevel
import com.telenebula.app.nav.Navigator
import com.telenebula.app.nav.RenewCertificate
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.notices.Prompt
import com.telenebula.app.platform.ActivityGateway
import com.telenebula.app.platform.AttachmentStore
import com.telenebula.app.platform.CertInspector
import com.telenebula.app.platform.Format
import com.telenebula.app.platform.IdentityStore
import com.telenebula.app.platform.OpenWith
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.platform.userMessage
import com.telenebula.app.runtime.AppRuntime
import com.telenebula.core.CoreClient
import com.telenebula.core.model.Profile
import com.telenebula.app.ui.shared.uiState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class AccountUiState(
    val certName: String = "",
    val overlayIp: String = "",
    val certStatusText: String = "",
    val certStatusLevel: CertExpiryLevel = CertExpiryLevel.WARNING,
    val isBackupBusy: Boolean = false,
)

class AccountViewModel(
    private val runtime: AppRuntime,
    private val core: CoreClient,
    private val identity: IdentityStore,
    private val prefs: PrefsRepository,
    private val gateway: ActivityGateway,
    private val files: AttachmentStore,
    private val openWith: OpenWith,
    private val notices: NoticeCenter,
    private val navigator: Navigator,
) : ViewModel() {
    private val isBusy = MutableStateFlow(false)

    val uiState: StateFlow<AccountUiState> = combine(runtime.profile, isBusy, ::buildState)
        .uiState(viewModelScope, buildState(runtime.profile.value, isBusy.value))

    // runtime.profile is already a live StateFlow now — seeding blank is what flashes an empty
    // account card for a frame on every revisit (WhileSubscribed tears this combine down 5s after
    // the screen closes).
    private fun buildState(profile: Profile?, busy: Boolean): AccountUiState {
        val status = profile?.let { CertInspector.expiryStatus(it.certNotAfter) }
        return AccountUiState(
            certName = profile?.certName.orEmpty(),
            overlayIp = profile?.overlayIp.orEmpty(),
            certStatusText = status?.text.orEmpty(),
            certStatusLevel = status?.level ?: CertExpiryLevel.WARNING,
            isBackupBusy = busy,
        )
    }

    fun openRenewCertificate() = navigator.push(RenewCertificate)
    fun goBack() = navigator.pop()

    fun exportBackup() {
        if (isBusy.value) return
        isBusy.value = true
        viewModelScope.launch {
            try {
                val summary = notices.withLoading("Copying the database, settings and media…") { core.createBackup(BuildConfig.VERSION_NAME) }
                openWith.shareFile(File(summary.path), "application/x-tar")
                notices.setSuccess(
                    "Backup ready: ${summary.contacts} contacts · ${summary.messages} messages · ${summary.attachments} files · ${Format.bytes(summary.bytes)}. Your private key is not in the file.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addError("Couldn't create the backup: ${e.userMessage()}")
            } finally {
                isBusy.value = false
            }
        }
    }

    fun restoreBackup() {
        if (isBusy.value) return
        viewModelScope.launch {
            val uri = gateway.pickDocument() ?: return@launch
            val name = files.displayName(uri)
            notices.setPrompt(
                Prompt(
                    message = "Restore backup\n\nAdd the contacts, chats and files from $name to this device? Nothing already here is deleted or changed.",
                    rightLabel = "Restore",
                    onRight = { runRestore(uri, name) },
                ),
            )
        }
    }

    private fun runRestore(uri: android.net.Uri, name: String) {
        isBusy.value = true
        viewModelScope.launch {
            try {
                val summary = notices.withLoading("Reading $name…") {
                    val archive = files.copyToCache(uri)
                    try {
                        core.importBackup(archive.absolutePath)
                    } finally {
                        archive.delete()
                    }
                }
                prefs.load()
                notices.setSuccess("Backup restored: ${summary.contacts} contacts, ${summary.messages} messages and ${summary.attachments} files were added.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addError("Couldn't restore the backup: ${e.userMessage()}")
            } finally {
                isBusy.value = false
            }
        }
    }

    fun confirmDeleteAllChats() = notices.setPrompt(
        Prompt(
            message = "Delete all chats\n\nEvery message and media file on this device is deleted. Contacts, settings and your identity stay.",
            rightLabel = "Delete",
            isDestructive = true,
            onRight = {
                viewModelScope.launch {
                    if (!core.clearAllHistory()) return@launch
                    core.clearOrphanAttachments()
                    notices.setSuccess("Chats deleted: Your contacts and identity are untouched.")
                }
            },
        ),
    )

    fun confirmReset() = notices.setPrompt(
        Prompt(
            message = "Reset identity\n\nThis deletes the uploaded certificates, private key and profile from this device. Continue?",
            rightLabel = "Reset",
            isDestructive = true,
            onRight = {
                viewModelScope.launch {
                    runtime.stopRuntime()
                    identity.resetIdentity()
                }
            },
        ),
    )
}

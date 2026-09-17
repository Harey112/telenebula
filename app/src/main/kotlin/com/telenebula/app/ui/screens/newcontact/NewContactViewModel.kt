package com.telenebula.app.ui.screens.newcontact

import android.Manifest
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Chat
import com.telenebula.app.nav.Navigator
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.platform.ActivityGateway
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.platform.QrPayloads
import com.telenebula.core.CoreClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewContactUiState(
    val ip: String = "",
    val nickname: String = "",
    val notes: String = "",
    /** username taken from a scanned profile QR */
    val scannedName: String = "",
    val isScannerOpen: Boolean = false,
    val hasCameraPermission: Boolean = false,
) {
    val canCreate: Boolean get() = ContactLabels.isOverlayIp(ip)
}

/**
 * A contact starts as "nickname + address". Its username is filled in by the core from the peer's
 * first hello/message — or right away from a scanned QR.
 */
@Stable
interface NewContactActions {
    fun handleScanned(data: String)
    fun handleScannerError()
    fun requestCameraPermission()
}

class NewContactViewModel(
    private val core: CoreClient,
    private val gateway: ActivityGateway,
    private val notices: NoticeCenter,
    private val navigator: Navigator,
) : ViewModel(), NewContactActions {
    private val mutable = MutableStateFlow(NewContactUiState(hasCameraPermission = gateway.hasPermission(Manifest.permission.CAMERA)))
    val uiState: StateFlow<NewContactUiState> = mutable.asStateFlow()

    /** A hand-edited address no longer belongs to the scanned identity. */
    fun setIp(value: String) = mutable.update { it.copy(ip = value, scannedName = "") }
    fun setNickname(value: String) = mutable.update { it.copy(nickname = value) }
    fun setNotes(value: String) = mutable.update { it.copy(notes = value) }
    fun openScanner() = mutable.update { it.copy(isScannerOpen = true) }
    fun closeScanner() = mutable.update { it.copy(isScannerOpen = false) }
    fun toggleScanner() = mutable.update { it.copy(isScannerOpen = !it.isScannerOpen) }
    fun goBack() = navigator.pop()

    override fun requestCameraPermission() {
        viewModelScope.launch {
            val granted = gateway.requestPermission(Manifest.permission.CAMERA)
            mutable.update { it.copy(hasCameraPermission = granted) }
        }
    }

    override fun handleScannerError() {
        mutable.update { it.copy(isScannerOpen = false) }
        notices.addWarning("Couldn't start the camera. Close other apps using it and try again.")
    }

    override fun handleScanned(data: String) {
        if (!mutable.value.isScannerOpen) return
        val payload = QrPayloads.decode(data)
        val ip = payload?.ip?.trim()?.lowercase().orEmpty()
        if (!ContactLabels.isOverlayIp(ip)) {
            mutable.update { it.copy(isScannerOpen = false) }
            notices.addWarning("Unrecognised QR code: That code doesn't look like a TeleNebula contact.")
            return
        }
        mutable.update { it.copy(ip = ip, scannedName = payload?.name?.trim().orEmpty(), isScannerOpen = false) }
    }

    fun createContact() {
        val s = mutable.value
        if (!s.canCreate) return
        val ip = s.ip.trim().lowercase()
        viewModelScope.launch {
            core.upsertContact(ip, s.scannedName)
            core.updateContactDetails(ip, s.scannedName, s.nickname.trim(), s.notes.trim())
            notices.setSuccess("${s.nickname.trim().ifEmpty { s.scannedName.ifEmpty { ip } }} was added to your contacts.")
            navigator.pop()
            navigator.push(Chat(ip))
        }
    }
}

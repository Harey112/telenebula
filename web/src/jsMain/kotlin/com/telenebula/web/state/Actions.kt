package com.telenebula.web.state

import com.telenebula.web.wire.DexContactFlags
import com.telenebula.web.wire.DexContactNotifications
import com.telenebula.web.wire.DexContactPrivacy
import com.telenebula.web.wire.DexMessage
import com.telenebula.web.wire.DexSettingsPatch
import org.w3c.files.File

/** Everything the views may ask for; the app owns the logic behind each. */
interface Actions {
    fun login(username: String, password: String)
    fun logout()
    fun openChat(peer: String)
    fun closeChat()
    fun setSearch(text: String)
    fun toggleArchived()
    fun loadMore()
    fun sendText(text: String): Boolean
    fun typing(isTyping: Boolean)
    fun markRead()
    fun reply(msg: DexMessage?)
    fun toggleCover()
    fun startEdit(msg: DexMessage?)
    fun react(msg: DexMessage, emoji: String)
    fun deleteMessage(msg: DexMessage, forEveryone: Boolean)
    fun copyMessage(msg: DexMessage)
    fun retrySend(msg: DexMessage)
    fun cancelSend(msg: DexMessage)
    fun acceptOffer(msg: DexMessage)
    fun declineOffer(msg: DexMessage)
    fun cancelTransfer(msg: DexMessage)
    fun reveal(msg: DexMessage)
    fun openLightbox(id: String?)
    fun openMenu(id: String?)
    fun openReactions(id: String?)
    fun toggleEmoji()
    fun attach(files: List<File>)
    fun cancelUpload(id: Int)
    fun dismissUpload(id: Int)
    fun startRecording()
    fun stopRecording()
    fun discardRecording()
    fun sendRecording()
    fun dismissToast(id: Int)
    fun scrolledToBottom()
    fun confirmPrompt()
    fun dismissPrompt()
    fun startCall(video: Boolean)
    fun acceptCall()
    fun rejectCall()
    fun endCall()
    fun toggleMute()
    fun toggleCamera()
    fun moveCallToPhone()
    val isMuted: Boolean
    val isCameraOn: Boolean

    // --- shell ---
    fun openTab(tab: Tab)
    fun toggleRail()
    fun openSettingsTab(tab: SettingsTab)
    fun openDialog(dialog: Dialog?)
    fun updateDialog(dialog: Dialog)
    fun submitDialog()

    // --- settings ---
    fun patchSettings(patch: DexSettingsPatch)
    fun setQuickReaction(slot: Int, emoji: String)
    fun setTunnel(isOn: Boolean)
    fun clearOrphans()
    fun clearAllHistory()
    fun checkUpdates()
    fun retryFailed(peer: String)
    fun drain(peer: String)

    // --- contacts ---
    fun setContactSearch(text: String)
    fun selectContact(peer: String?)
    fun saveContact(peer: String, name: String, nickname: String, notes: String)
    fun addContact(ip: String, name: String, nickname: String, notes: String)
    fun deleteContact(peer: String)
    fun setContactFlags(peer: String, flags: DexContactFlags)
    fun setContactPrivacy(peer: String, privacy: DexContactPrivacy)
    fun setContactNotifications(peer: String, prefs: DexContactNotifications?)
    fun changeContactIp(peer: String, newIp: String)
    fun clearHistory(peer: String)
    fun pingPeer(peer: String)

    // --- calls ---
    fun setCallFilter(filter: CallFilter)
    fun setCallSearch(text: String)
    fun toggleCallSelected(id: String)
    fun clearCallSelection()
    fun deleteSelectedCalls()
    fun callPeer(peer: String, video: Boolean)

    // --- chat info ---
    fun toggleInfo()
    fun searchChat(text: String)
}

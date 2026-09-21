package com.telenebula.dex

import com.telenebula.dex.wire.ClientFrame
import com.telenebula.dex.wire.DexAccount
import com.telenebula.dex.wire.DexCallLog
import com.telenebula.dex.wire.DexCallState
import com.telenebula.dex.wire.DexChat
import com.telenebula.dex.wire.DexChatLink
import com.telenebula.dex.wire.DexChatView
import com.telenebula.dex.wire.DexContact
import com.telenebula.dex.wire.DexContactDetail
import com.telenebula.dex.wire.DexContactFlags
import com.telenebula.dex.wire.DexContactNotifications
import com.telenebula.dex.wire.DexContactPrivacy
import com.telenebula.dex.wire.DexDiagnostics
import com.telenebula.dex.wire.DexIceCandidate
import com.telenebula.dex.wire.DexIdentity
import com.telenebula.dex.wire.DexMessage
import com.telenebula.dex.wire.DexNetwork
import com.telenebula.dex.wire.DexPingResult
import com.telenebula.dex.wire.DexPresence
import com.telenebula.dex.wire.DexQueue
import com.telenebula.dex.wire.DexSettings
import com.telenebula.dex.wire.DexSettingsPatch
import com.telenebula.dex.wire.DexStorage
import com.telenebula.dex.wire.DexUpdates
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A file the server may stream to a browser. */
class DexFile(val file: File, val name: String, val mime: String)

/** An upload the browser finished; the backend turns it into a message. */
class DexUpload(
    val peer: String,
    val file: File,
    val name: String,
    val mime: String,
    val size: Long,
    val isVoice: Boolean,
    val durationMs: Long?,
    val replyTo: String?,
    val isCovered: Boolean,
)

/** What the browser did about a call; [clientId] is the socket it came from. */
sealed interface DexCallCommand {
    val clientId: String

    class Start(override val clientId: String, val peer: String, val video: Boolean) : DexCallCommand
    class Accept(override val clientId: String, val callId: String) : DexCallCommand
    class Reject(override val clientId: String, val callId: String) : DexCallCommand
    class End(override val clientId: String, val callId: String) : DexCallCommand
    class Sdp(override val clientId: String, val callId: String, val sdp: String, val sdpType: String) : DexCallCommand
    class Ice(override val clientId: String, val callId: String, val candidate: DexIceCandidate?) : DexCallCommand
    class Connected(override val clientId: String, val callId: String) : DexCallCommand
    class Failed(override val clientId: String, val callId: String, val reason: String) : DexCallCommand
    class Cam(override val clientId: String, val callId: String, val isOn: Boolean) : DexCallCommand
    class MoveToPhone(override val clientId: String, val callId: String) : DexCallCommand
    /** the socket closed; whatever it owned is gone */
    class Gone(override val clientId: String) : DexCallCommand
}

/** What the phone tells one browser about the call's media, beyond the shared state. */
sealed interface DexCallEvent {
    val clientId: String
    val callId: String

    class Media(
        override val clientId: String,
        override val callId: String,
        val isOfferer: Boolean,
        val video: Boolean,
        val remoteSdp: String?,
        val remoteSdpType: String?,
        val isRestart: Boolean,
    ) : DexCallEvent
    class Sdp(override val clientId: String, override val callId: String, val sdp: String, val sdpType: String) : DexCallEvent
    class Ice(override val clientId: String, override val callId: String, val candidate: DexIceCandidate?) : DexCallEvent
    class Release(override val clientId: String, override val callId: String, val reason: String) : DexCallEvent
}

/**
 * Everything the Dex server needs from the phone, in the wire's own types. The app implements it
 * over the messaging core and the call engine; the server never sees either.
 */
interface DexBackend {
    /** null until the phone is set up */
    val me: StateFlow<DexIdentity?>

    /** the overlay networks, CIDR; a connection from one of them is never a browser */
    val overlayNetworks: StateFlow<List<String>>

    fun chats(): Flow<List<DexChat>>
    fun contacts(): Flow<List<DexContact>>
    fun chat(peer: String): Flow<DexChatView>
    suspend fun messagesBefore(peer: String, beforeTs: Long, beforeId: String, limit: Int): List<DexMessage>
    fun presence(): Flow<Map<String, DexPresence>>
    fun typing(): Flow<Set<String>>
    fun queues(): Flow<List<DexQueue>>
    suspend fun freeBytes(): Long

    suspend fun sendText(peer: String, body: String, replyTo: String?, isCovered: Boolean)
    /** where an upload lands while it streams in; the backend owns the directory */
    fun newUploadFile(name: String): File
    suspend fun sendUpload(upload: DexUpload)
    /** null when there is no such message, it has no file, or the file may not leave the phone */
    suspend fun attachment(messageId: String): DexFile?
    fun sendTyping(peer: String, isTyping: Boolean)
    suspend fun markRead(peer: String)
    suspend fun react(messageId: String, emoji: String)
    suspend fun edit(messageId: String, body: String)
    suspend fun delete(messageId: String, forEveryone: Boolean)
    suspend fun retryAction(actionId: String)
    suspend fun cancelAction(actionId: String)
    suspend fun acceptOffer(messageId: String)
    suspend fun declineOffer(messageId: String)
    suspend fun cancelTransfer(messageId: String)

    val callState: StateFlow<DexCallState>
    val callEvents: Flow<DexCallEvent>
    fun onCallCommand(command: DexCallCommand)

    // --- settings ------------------------------------------------------------------------------

    fun settings(): Flow<DexSettings>

    /** Applies the fields the patch carries; what only a phone can decide is refused here. */
    suspend fun applySettings(patch: DexSettingsPatch)
    suspend fun setQuickReaction(slot: Int, emoji: String)

    // --- what the phone reports; each is collected only while a browser watches it --------------

    fun account(): Flow<DexAccount>
    fun network(): Flow<DexNetwork>
    fun storage(): Flow<DexStorage>
    fun diagnostics(): Flow<DexDiagnostics>
    fun updates(): Flow<DexUpdates>

    // --- contacts ------------------------------------------------------------------------------

    /** null when no such contact is saved */
    suspend fun contactDetail(peer: String): DexContactDetail?
    suspend fun saveContact(peer: String, name: String, nickname: String, notes: String)
    suspend fun addContact(peer: String, name: String, nickname: String, notes: String)
    suspend fun deleteContact(peer: String)
    suspend fun setContactFlags(peer: String, flags: DexContactFlags)
    suspend fun setContactPrivacy(peer: String, privacy: DexContactPrivacy)
    suspend fun setContactNotifications(peer: String, prefs: DexContactNotifications?)
    suspend fun changeContactIp(peer: String, newIp: String)

    // --- history and storage ---------------------------------------------------------------------

    suspend fun clearHistory(peer: String)
    suspend fun clearAllHistory()
    suspend fun clearOrphans(): Long

    suspend fun callLogs(peer: String?, limit: Int): List<DexCallLog>
    suspend fun deleteCallLogs(ids: List<String>)
    suspend fun chatMedia(peer: String): List<DexMessage>
    suspend fun chatLinks(peer: String): List<DexChatLink>
    suspend fun search(peer: String, text: String): List<DexMessage>
    suspend fun forward(messageId: String, peer: String)

    // --- network ---------------------------------------------------------------------------------

    suspend fun pingPeer(peer: String): DexPingResult
    suspend fun retryFailed(peer: String)
    suspend fun drain(peer: String)
    suspend fun checkUpdates()

    /** Throws when the tunnel cannot be switched from here, which the first time it always is. */
    suspend fun setTunnel(isOn: Boolean)

    /** Turns one frame the wire could not name into a message for the browser; the server reports it. */
    fun describe(error: Throwable): String = error.message ?: error.javaClass.simpleName
}

/** A frame a browser sent, already typed, from a known socket. */
class DexClientMessage(val clientId: String, val frame: ClientFrame)

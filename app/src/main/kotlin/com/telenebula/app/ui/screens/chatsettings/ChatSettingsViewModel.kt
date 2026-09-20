package com.telenebula.app.ui.screens.chatsettings

import com.telenebula.app.nav.ChatLinks
import com.telenebula.app.nav.ChatMedia
import com.telenebula.app.nav.ChatNotifications
import com.telenebula.app.nav.Contact as ContactKey
import com.telenebula.app.nav.Navigator
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.notices.Prompt
import com.telenebula.app.platform.AppLock
import com.telenebula.app.platform.AttachmentStore
import com.telenebula.app.platform.ContactLabels
import com.telenebula.app.platform.FileIo
import com.telenebula.app.platform.Format
import com.telenebula.app.platform.MUTE_FOREVER
import com.telenebula.app.platform.OpenWith
import com.telenebula.app.platform.userMessage
import com.telenebula.app.sheets.MediaViewerCenter
import com.telenebula.app.ui.fragments.MenuOption
import com.telenebula.app.ui.fragments.SelectOption
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.shared.ChatSearchRequests
import com.telenebula.app.ui.shared.CoverGates
import com.telenebula.app.ui.shared.openAttachment
import com.telenebula.core.CoreClient
import com.telenebula.core.PeerQueueStore
import com.telenebula.core.model.ContactPrivacyPrefs
import com.telenebula.core.model.ChatLink
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.Contact
import com.telenebula.core.model.ContactFlagsPatch
import com.telenebula.core.model.PeerQueueState
import com.telenebula.app.ui.shared.uiState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Disappearing-message timer choices (seconds; 0 = off). */
val DISAPPEAR_OPTIONS: List<Pair<String, Int>> = listOf("Off" to 0, "30 seconds" to 30, "1 minute" to 60, "1 hour" to 3600, "1 day" to 86_400, "1 week" to 604_800)

/** A per-chat privacy switch: follow the global setting, or force it on or off. */
enum class PrivacyChoice(val key: String, val value: Boolean?) {
    DEFAULT("default", null),
    ON("on", true),
    OFF("off", false);

    companion object {
        fun of(value: Boolean?): PrivacyChoice = entries.first { it.value == value }
        fun fromKey(key: String): PrivacyChoice = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

data class ChatSettingsUiState(
    val ip: String,
    val peerName: String = "",
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
    val isBlocked: Boolean = false,
    val readReceipts: PrivacyChoice = PrivacyChoice.DEFAULT,
    val typingIndicators: PrivacyChoice = PrivacyChoice.DEFAULT,
    val blockScreenshots: PrivacyChoice = PrivacyChoice.DEFAULT,
    val revealGateKey: String = CoverGates.DEFAULT_KEY,
    val openMenuKey: String? = null,
    val disappearSeconds: Int = 0,
    val failedCount: Int = 0,
    /** actions still waiting to reach this peer */
    val queuedCount: Int = 0,
    val isPeerReachable: Boolean = true,
    val isPeerSending: Boolean = false,
    val nextProbeInMs: Long? = null,
    val mediaPreview: List<ChatMessage> = emptyList(),
    val mediaCount: Int = 0,
    val linksPreview: List<ChatLink> = emptyList(),
    val linksCount: Int = 0,
    val isDisappearMenuOpen: Boolean = false,
) {
    val disappearLabel: String get() = if (disappearSeconds > 0) Format.seconds(disappearSeconds) else "Off"

    /** items beyond the preview, shown on its last tile */
    val mediaMore: Int get() = (mediaCount - mediaPreview.size).coerceAtLeast(0)
    val mediaLabel: String get() = countLabel(mediaCount, "item", mediaMore)
    val linksMore: Int get() = (linksCount - linksPreview.size).coerceAtLeast(0)
    val linksLabel: String get() = countLabel(linksCount, "link", linksMore)

    private fun countLabel(total: Int, noun: String, more: Int): String {
        val all = "$total $noun${if (total == 1) "" else "s"}"
        return if (more > 0) "$all · $more more than shown" else all
    }

    /** What is waiting for this peer and when it will next be tried. */
    val queueLabel: String
        get() = when {
            queuedCount == 0 -> "Nothing waiting — tap to check anyway"
            isPeerSending -> "$queuedCount waiting · sending now"
            nextProbeInMs != null -> "$queuedCount waiting · next try in ${Format.seconds((nextProbeInMs / 1000).toInt().coerceAtLeast(1))}"
            else -> "$queuedCount waiting — tap to send now"
        }
}

/** Per-chat options plus what the chat holds: media, links, and the danger zone. */
class ChatSettingsViewModel(
    private val ip: String,
    private val core: CoreClient,
    private val files: AttachmentStore,
    private val openWith: OpenWith,
    private val viewer: MediaViewerCenter,
    private val peerQueues: PeerQueueStore,
    private val chatSearch: ChatSearchRequests,
    private val appLock: AppLock,
    private val notices: NoticeCenter,
    private val navigator: Navigator,
) : ViewModel() {
    private data class Local(val isDisappearMenuOpen: Boolean = false, val openMenu: String? = null)

    private class Content(val failed: Int, val media: List<ChatMessage>, val mediaCount: Int, val links: List<ChatLink>, val linksCount: Int)

    private val local = MutableStateFlow(Local())
    // a handful of rows and a count: the screen shows a preview, not the whole gallery
    private val content = core.chatChanges(ip).mapLatest {
        Content(
            failed = core.peerStats(ip).failedActions,
            media = core.chatMedia(ip, PREVIEW_MEDIA),
            mediaCount = core.chatMediaCount(ip),
            links = core.chatLinks(ip, PREVIEW_LINKS).take(PREVIEW_LINKS),
            linksCount = core.chatLinkMessageCount(ip),
        )
    }

    // the toggles come from the cached contact at once; only the media/link counts wait for their query
    val uiState: StateFlow<ChatSettingsUiState> = combine(core.contactFlow(ip), content, peerQueues.queues, local) { contact, c, queues, l ->
        build(contact, c, queues[ip], l)
    }.uiState(
        viewModelScope,
        build(core.cachedContact(ip), Content(0, emptyList(), 0, emptyList(), 0), peerQueues.of(ip), local.value),
    )

    private fun build(contact: Contact?, c: Content, queue: PeerQueueState?, l: Local): ChatSettingsUiState = ChatSettingsUiState(
        ip = ip,
        peerName = contact?.let(ContactLabels::chatLabel) ?: ip,
        isMuted = contact != null && ContactLabels.isMuted(contact.muteUntil),
        isArchived = contact?.isArchived == true,
        isBlocked = contact?.isBlocked == true,
        readReceipts = PrivacyChoice.of(contact?.privacy?.sendReadReceipts),
        typingIndicators = PrivacyChoice.of(contact?.privacy?.sendTypingIndicators),
        blockScreenshots = PrivacyChoice.of(contact?.privacy?.blockScreenshots),
        revealGateKey = CoverGates.keyOf(contact?.privacy?.revealGate),
        openMenuKey = l.openMenu,
        disappearSeconds = contact?.disappearSeconds ?: 0,
        failedCount = c.failed,
        queuedCount = queue?.queued ?: 0,
        isPeerReachable = queue?.isReachable ?: true,
        isPeerSending = queue?.isDraining == true,
        nextProbeInMs = queue?.nextProbeInMs,
        mediaPreview = c.media,
        mediaCount = c.mediaCount,
        linksPreview = c.links,
        linksCount = c.linksCount,
        isDisappearMenuOpen = l.isDisappearMenuOpen,
    )

    private fun flags(patch: ContactFlagsPatch) = viewModelScope.launch { core.setContactFlags(ip, patch) }

    val privacyOptions: List<SelectOption> = PRIVACY_OPTIONS

    fun setReadReceipts(key: String) = privacy { it.copy(sendReadReceipts = PrivacyChoice.fromKey(key).value) }

    fun setTypingIndicators(key: String) = privacy { it.copy(sendTypingIndicators = PrivacyChoice.fromKey(key).value) }

    fun setBlockScreenshots(key: String) = privacy { it.copy(blockScreenshots = PrivacyChoice.fromKey(key).value) }

    val coverGateOptions: List<SelectOption> = CoverGates.options(appLock.canUseDeviceAuth(), withDefault = true)

    fun setRevealGate(key: String) = privacy { it.copy(revealGate = CoverGates.of(key)) }

    fun openMenu(key: String) = local.update { it.copy(openMenu = key) }

    fun closeMenu() = local.update { it.copy(openMenu = null) }

    private fun privacy(transform: (ContactPrivacyPrefs) -> ContactPrivacyPrefs) {
        val s = uiState.value
        val current = ContactPrivacyPrefs(
            sendReadReceipts = s.readReceipts.value,
            sendTypingIndicators = s.typingIndicators.value,
            blockScreenshots = s.blockScreenshots.value,
            revealGate = CoverGates.of(s.revealGateKey),
        )
        viewModelScope.launch { core.setContactPrivacy(ip, transform(current)) }
    }

    fun openContact() = navigator.push(ContactKey(ip))
    fun openNotificationSettings() = navigator.push(ChatNotifications(ip))
    fun openMedia() = navigator.push(ChatMedia(ip))
    fun openLinks() = navigator.push(ChatLinks(ip))
    fun openDisappearMenu() = local.update { it.copy(isDisappearMenuOpen = true) }
    fun closeDisappearMenu() = local.update { it.copy(isDisappearMenuOpen = false) }
    fun toggleMute() = flags(ContactFlagsPatch(muteUntil = if (uiState.value.isMuted) 0L else MUTE_FOREVER))
    fun toggleArchive() = flags(ContactFlagsPatch(isArchived = !uiState.value.isArchived))
    fun goBack() = navigator.pop()

    fun disappearMenu(current: Int): List<MenuOption> = DISAPPEAR_OPTIONS.map { (label, seconds) ->
        MenuOption(seconds.toString(), if (seconds == current) TnIcon.CHECK else TnIcon.CLOCK, label) {
            local.update { it.copy(isDisappearMenuOpen = false) }
            flags(ContactFlagsPatch(disappearSeconds = seconds))
        }
    }

    fun toggleBlock() {
        if (uiState.value.isBlocked) {
            flags(ContactFlagsPatch(isBlocked = false))
            return
        }
        notices.setPrompt(
            Prompt(
                message = "Block contact\n\nBlock ${uiState.value.peerName}? Their messages and calls will be dropped.",
                rightLabel = "Block",
                isDestructive = true,
                onRight = { flags(ContactFlagsPatch(isBlocked = true)) },
            ),
        )
    }

    fun exportChat() {
        val peerName = uiState.value.peerName
        viewModelScope.launch {
            try {
                val text = core.exportChatText(ip, peerName)
                if (text.isEmpty()) {
                    notices.setSuccess("Nothing to export: This chat has no messages yet.")
                    return@launch
                }
                notices.withLoading("Writing the transcript for $peerName…") {
                    val file = files.cacheFile("telenebula-chat-${ip.replace(Regex("[^a-zA-Z0-9]"), "_")}.txt")
                    FileIo.writeAtomic(file, text)
                    openWith.shareFile(file, "text/plain")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addError("Couldn't export the chat: ${e.userMessage()}")
            }
        }
    }

    /** Back to the chat, asking it to open its search field there. */
    fun openSearch() {
        chatSearch.open(ip)
        navigator.pop()
    }

    /**
     * "Send it already". Nothing is queued per action any more, so this is a statement about the
     * peer: probe it now and move everything it is holding, instead of waiting for its next probe.
     */
    fun sendQueuedNow() {
        viewModelScope.launch {
            val queued = uiState.value.queuedCount
            if (queued == 0) {
                notices.setSuccess("Nothing queued: everything for this chat has been delivered.")
                return@launch
            }
            core.drainNow(ip)
            notices.setSuccess("Sending now: trying ${if (queued == 1) "1 queued action" else "$queued queued actions"}.")
        }
    }

    fun retryFailed() {
        viewModelScope.launch {
            val count = core.retryFailedActions(ip)
            notices.setSuccess("Retrying: ${if (count == 0) "Nothing had failed." else "$count action(s) re-queued."}")
        }
    }

    fun openMediaItem(msg: ChatMessage) {
        val target = openAttachment(msg, openWith, notices) ?: return
        viewer.open(target)
    }

    fun openLink(url: String) {
        try {
            openWith.openUrl(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notices.addWarning("Couldn't open that link: $url")
        }
    }

    fun clearHistory() = notices.setPrompt(
        Prompt(
            message = "Clear history\n\nDelete all messages in this chat on this device?",
            rightLabel = "Clear",
            isDestructive = true,
            onRight = {
                viewModelScope.launch {
                    core.clearHistory(ip)
                    notices.setSuccess("Chat history was cleared.")
                }
            },
        ),
    )

    fun deleteChat() = notices.setPrompt(
        Prompt(
            message = "Delete chat\n\nDelete the chat and contact ${uiState.value.peerName}?",
            rightLabel = "Delete",
            isDestructive = true,
            onRight = {
                viewModelScope.launch {
                    core.deleteContact(ip)
                    navigator.dismissToTabRoot()
                }
            },
        ),
    )

    private companion object {
        val PRIVACY_OPTIONS = listOf(SelectOption("default", "Default"), SelectOption("on", "On"), SelectOption("off", "Off"))
        const val PREVIEW_MEDIA = 4
        const val PREVIEW_LINKS = 3
    }
}

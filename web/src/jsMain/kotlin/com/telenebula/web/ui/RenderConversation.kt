package com.telenebula.web.ui

import com.telenebula.web.net.Api
import com.telenebula.web.net.EmojiGroup
import com.telenebula.web.state.Actions
import com.telenebula.web.state.AppState
import com.telenebula.web.state.Dialog
import com.telenebula.web.state.EmojiTarget
import com.telenebula.web.state.Recording
import com.telenebula.web.state.Upload
import com.telenebula.web.util.Format
import com.telenebula.web.wire.DexCallPhase
import com.telenebula.web.wire.DexContact
import com.telenebula.web.wire.DexDirection
import com.telenebula.web.wire.DexMessage
import com.telenebula.web.wire.DexMessageKind
import com.telenebula.web.wire.DexMessageStatus
import com.telenebula.web.wire.DexPresence
import com.telenebula.web.wire.DexSendState
import kotlinx.browser.window
import org.w3c.dom.HTMLAudioElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.asList
import org.w3c.dom.events.KeyboardEvent
import org.w3c.files.File
import org.w3c.files.get
import kotlin.js.Date

sealed interface TimelineItem {
    val key: String

    data class Day(val ts: Long, val label: String) : TimelineItem {
        override val key: String get() = "d:$ts"
    }

    data class Row(
        val msg: DexMessage,
        val isRevealed: Boolean,
        val peerLabel: String,
        val meIp: String,
        val freeBytes: Long,
        val isMenuOpen: Boolean,
        val isReactOpen: Boolean,
    ) : TimelineItem {
        override val key: String get() = msg.id
    }
}

class ConversationView(root: HTMLElement, private val actions: Actions) {
    val host = div("pane pane-chat").also { root.appendChild(it) }

    private val backBtn = button("icon-btn back", "Back to chats", { actions.closeChat() }, Icon.BACK)
    private val title = div("chat-title")
    private val subtitle = div("chat-subtitle")
    private val callBtn = button("icon-btn", "Voice call", { actions.startCall(false) }, Icon.CALL)
    private val videoBtn = button("icon-btn", "Video call", { actions.startCall(true) }, Icon.VIDEO)
    private val infoBtn = button("icon-btn", "Chat details", { actions.toggleInfo() }, Icon.INFO)
    private val placeholder = div("chat-placeholder").add(svg(Icon.MONITOR, 40), div(null, "Pick a chat to start."))

    private val timelineHost = div("timeline").also { it.setAttribute("role", "log"); it.setAttribute("aria-live", "polite") }
    private val loadMore = div("load-more", "Loading…")
    private val items = div("timeline-items")
    private val newBelow = button("pill new-below hidden", "Scroll to newest messages", { scrollToBottom(true) }, Icon.ARROW_DOWN, "New messages")
    private val list = KeyedList<TimelineItem>(items, ::createItem, ::updateItem)
    private var quickReactions: List<String> = DEFAULT_QUICK_REACTIONS
    private var emojiGroups: List<EmojiGroup> = emptyList()
    private var isEnterToSend = false
    private var reactPeer = ""

    private val composer = div("composer")
    private val replyStrip = div("reply-strip hidden")
    private val uploadsHost = div("uploads")
    private val uploads = KeyedList<Upload>(uploadsHost, ::createUpload, ::updateUpload)
    private val recordingBar = div("recording hidden")
    private val emojiBar = div("emoji-bar hidden")
    private val textarea = el("textarea", "compose") { setAttribute("rows", "1"); setAttribute("placeholder", "Message"); setAttribute("aria-label", "Message") } as HTMLTextAreaElement
    private val fileInput = el("input") { setAttribute("type", "file"); setAttribute("multiple", ""); className = "hidden" } as HTMLInputElement
    private val coverBtn = button("icon-btn", "Cover message", { actions.toggleCover() }, Icon.UNLOCK)
    private val attachBtn = button("icon-btn", "Attach a file", { fileInput.click() }, Icon.ATTACH)
    private val emojiBtn = button("icon-btn", "Emoji", { actions.toggleEmoji() }, Icon.SMILE)
    private val micBtn = button("icon-btn", "Record a voice message", { actions.startRecording() }, Icon.MIC)
    private val sendBtn = button("icon-btn send", "Send", { submit() }, Icon.SEND)

    private var wasAtBottom = true
    private var lastMessageId: String? = null
    private var typingTimer: Int? = null
    private var lastTypingSentAt = 0L
    private var isRestoringScroll = false
    private var recordTimer: Int? = null
    private var expiryTimer: Int? = null

    init {
        val header = div("chat-header").add(backBtn, div("chat-heading").add(title, subtitle), div("chat-actions").add(callBtn, videoBtn, infoBtn))
        timelineHost.add(loadMore, items)
        composer.add(
            emojiBar,
            uploadsHost,
            replyStrip,
            recordingBar,
            div("compose-row").add(coverBtn, attachBtn, textarea, emojiBtn, micBtn, sendBtn),
            fileInput,
        )
        host.add(placeholder, header, div("timeline-wrap").add(timelineHost, newBelow), composer)

        timelineHost.on("scroll") { onScroll() }
        textarea.on("input") {
            autosize()
            onTyping()
        }
        textarea.on("keydown") { e ->
            val k = e as? KeyboardEvent ?: return@on
            val isSend = if (isEnterToSend) k.key == "Enter" && !k.shiftKey else k.key == "Enter" && (k.shiftKey || k.ctrlKey || k.metaKey)
            if (isSend) {
                k.preventDefault()
                submit()
            }
            if (k.key == "Escape") {
                actions.reply(null)
                actions.startEdit(null)
            }
        }
        fileInput.on("change") {
            val files = fileInput.files
            if (files != null) actions.attach((0 until files.length).mapNotNull { files[it] })
            fileInput.value = ""
        }
        window.addEventListener("focus", { actions.markRead() })
        items.on("click") { e -> if ((e.target as? HTMLElement)?.closest(".msg-menu, .react-bar") == null) closeMenus() }
    }

    private fun buildEmojiBar() {
        emojiBar.clear()
        val quick = div("emoji-quick")
        for (e in quickReactions) quick.add(button("emoji", "Insert $e", { insertText(e) }, text = e))
        emojiBar.add(quick, emojiPicker(emojiGroups) { e -> insertText(e) })
    }

    private fun closeMenus() {
        actions.openMenu(null)
        actions.openReactions(null)
    }

    private fun insertText(text: String) {
        val start = textarea.selectionStart ?: textarea.value.length
        val end = textarea.selectionEnd ?: start
        textarea.value = textarea.value.substring(0, start) + text + textarea.value.substring(end)
        textarea.selectionStart = start + text.length
        textarea.selectionEnd = start + text.length
        textarea.focus()
        autosize()
    }

    private fun submit() {
        val text = textarea.value.trim()
        if (text.isEmpty()) return
        if (actions.sendText(text)) {
            textarea.value = ""
            autosize()
            cancelTyping(sendFalse = true)
            scrollToBottom(false)
        }
    }

    private fun autosize() {
        textarea.style.height = "auto"
        textarea.style.height = "${minOf(textarea.scrollHeight, 160)}px"
    }

    private fun onTyping() {
        val now = Date.now().toLong()
        if (textarea.value.isEmpty()) {
            cancelTyping(sendFalse = true)
            return
        }
        if (now - lastTypingSentAt > 3_000) {
            lastTypingSentAt = now
            actions.typing(true)
        }
        typingTimer?.let(window::clearTimeout)
        typingTimer = window.setTimeout({
            typingTimer = null
            lastTypingSentAt = 0
            actions.typing(false)
        }, 4_000)
    }

    private fun cancelTyping(sendFalse: Boolean) {
        typingTimer?.let(window::clearTimeout)
        typingTimer = null
        if (sendFalse && lastTypingSentAt != 0L) actions.typing(false)
        lastTypingSentAt = 0
    }

    private fun onScroll() {
        if (isRestoringScroll) return
        wasAtBottom = isAtBottom()
        if (wasAtBottom) {
            newBelow.toggle("hidden", true)
            actions.scrolledToBottom()
        }
        if (timelineHost.scrollTop < 80 && !loadMore.classList.contains("hidden")) actions.loadMore()
    }

    private fun isAtBottom(): Boolean = timelineHost.scrollHeight - timelineHost.scrollTop - timelineHost.clientHeight < 40

    private fun scrollToBottom(smooth: Boolean) {
        timelineHost.asDynamic().scrollTo(kotlin.js.json("top" to timelineHost.scrollHeight, "behavior" to if (smooth) "smooth" else "auto"))
        wasAtBottom = true
        newBelow.toggle("hidden", true)
    }

    fun render(prev: AppState, next: AppState) {
        host.toggle("hidden", next.tab != com.telenebula.web.state.Tab.CHATS)
        val peer = next.openPeer
        host.toggle("empty", peer == null)
        infoBtn.toggle("on", next.isInfoOpen)
        placeholder.toggle("hidden", peer != null)
        val view = next.view
        if (peer == null) {
            if (prev.openPeer != null) {
                list.clear()
                lastMessageId = null
                textarea.value = ""
                cancelTyping(sendFalse = false)
            }
            return
        }
        val contact = view?.contact ?: next.contacts[peer]
        val label = contact?.label ?: next.chats.firstOrNull { it.peer == peer }?.label ?: peer
        if (prev.openPeer != peer || prev.view !== view || prev.presence !== next.presence || prev.typing !== next.typing || prev.call !== next.call) {
            title.textContent = label
            subtitle.textContent = when {
                peer in next.typing -> "typing…"
                next.presence[peer] == DexPresence.ONLINE -> "Online"
                next.presence[peer] == DexPresence.REACHABLE -> "Reachable"
                else -> Format.lastSeen(contact?.lastSeenAt)
            }
            subtitle.toggle("accent", peer in next.typing || next.presence[peer] == DexPresence.ONLINE)
            val canCall = next.call.phase == DexCallPhase.IDLE || next.call.phase == DexCallPhase.ENDED
            (callBtn as? org.w3c.dom.HTMLButtonElement)?.disabled = !canCall || contact?.isBlocked == true
            (videoBtn as? org.w3c.dom.HTMLButtonElement)?.disabled = !canCall || contact?.isBlocked == true
        }

        if (prev.openPeer != peer) {
            list.clear()
            lastMessageId = null
            wasAtBottom = true
            textarea.value = ""
            autosize()
            cancelTyping(sendFalse = false)
        }
        val quick = next.quickReactions
        val hasQuickChanged = quick != quickReactions
        quickReactions = quick
        emojiGroups = next.emoji
        isEnterToSend = next.isEnterToSend
        reactPeer = peer.orEmpty()
        if (prev.view !== view || prev.revealed !== next.revealed || prev.menuFor != next.menuFor || prev.reactFor != next.reactFor || prev.openPeer != peer || prev.isLoadingMore != next.isLoadingMore || hasQuickChanged) {
            renderTimeline(prev, next, view?.messages.orEmpty(), label, contact, view?.freeBytes ?: next.freeBytes, view?.hasMore == true, next.isLoadingMore)
        }
        renderComposer(prev, next)
    }

    private fun renderTimeline(prev: AppState, next: AppState, messages: List<DexMessage>, label: String, contact: DexContact?, freeBytes: Long, hasMore: Boolean, isLoadingMore: Boolean) {
        val meIp = next.me?.ip.orEmpty()
        val built = ArrayList<TimelineItem>(messages.size + 16)
        var lastDay = -1L
        for (m in messages) {
            val day = Date(m.ts.toDouble()).let { Date(it.getFullYear(), it.getMonth(), it.getDate()).getTime().toLong() }
            if (day != lastDay) {
                built.add(TimelineItem.Day(day, Format.dayLabel(m.ts)))
                lastDay = day
            }
            built.add(TimelineItem.Row(m, m.id in next.revealed || !m.isCovered, label, meIp, freeBytes, next.menuFor == m.id, next.reactFor == m.id))
        }
        val previousHeight = timelineHost.scrollHeight
        val previousTop = timelineHost.scrollTop
        val isPrepend = prev.view != null && prev.view.peer == next.openPeer && messages.isNotEmpty() && prev.view.messages.isNotEmpty() && messages.first().id != prev.view.messages.first().id && messages.lastOrNull()?.id == prev.view.messages.lastOrNull()?.id
        val stickToBottom = wasAtBottom || prev.openPeer != next.openPeer
        list.render(built) { it.key }
        loadMore.toggle("hidden", !hasMore)
        loadMore.textContent = if (isLoadingMore) "Loading…" else "Scroll up for older messages"
        val newest = messages.lastOrNull()?.id
        isRestoringScroll = true
        when {
            isPrepend -> timelineHost.scrollTop = previousTop + (timelineHost.scrollHeight - previousHeight)
            stickToBottom -> scrollToBottom(false)
            newest != null && newest != lastMessageId && messages.lastOrNull()?.dir == DexDirection.IN -> newBelow.toggle("hidden", false)
        }
        isRestoringScroll = false
        lastMessageId = newest
        if (messages.any { it.dir == DexDirection.IN && !it.isRead }) actions.markRead()
        scheduleExpiryTick(messages.any { it.expiresAt != null })
    }

    private fun scheduleExpiryTick(hasExpiring: Boolean) {
        expiryTimer?.let(window::clearInterval)
        expiryTimer = null
        if (!hasExpiring) return
        expiryTimer = window.setInterval({
            items.querySelectorAll(".expires").asList().forEach { n ->
                val e = n as? HTMLElement ?: return@forEach
                val until = e.getAttribute("data-until")?.toLongOrNull() ?: return@forEach
                e.querySelector("span")?.textContent = Format.remaining(until)
            }
        }, 30_000)
    }

    // --- timeline items ---------------------------------------------------------------------

    private fun createItem(item: TimelineItem): HTMLElement = when (item) {
        is TimelineItem.Day -> div("day-sep").add(span("day-pill", item.label))
        is TimelineItem.Row -> div("msg-row").also { updateRow(it, null, item) }
    }

    private fun updateItem(node: HTMLElement, previous: TimelineItem, item: TimelineItem) {
        when (item) {
            is TimelineItem.Day -> node.querySelector(".day-pill")?.textContent = item.label
            is TimelineItem.Row -> updateRow(node, previous as? TimelineItem.Row, item)
        }
    }

    private fun updateRow(node: HTMLElement, previous: TimelineItem.Row?, row: TimelineItem.Row) {
        val m = row.msg
        val mine = m.dir == DexDirection.OUT
        node.className = "msg-row " + (if (mine) "out" else "in") + (if (m.send == DexSendState.FAILED) " failed" else "")
        val keepMedia = previous != null && previous.msg.att == m.att && previous.msg.status == m.status && previous.isRevealed == row.isRevealed &&
            previous.msg.isDeleted == m.isDeleted && previous.msg.kind == m.kind && previous.msg.transferPct == m.transferPct && previous.freeBytes == row.freeBytes
        val media = if (keepMedia) node.querySelector(".media") as? HTMLElement else null
        node.clear()
        val bubble = div("bubble" + if (m.isCovered && !row.isRevealed) " covered" else "")
        if (m.replyTo != null && !m.isDeleted) {
            bubble.appendChild(div("quote").add(div("quote-name", m.replyTo.name), div("quote-text", m.replyTo.snippet)))
        }
        when {
            m.isCovered && !row.isRevealed -> {
                val cover = div("cover").add(svg(Icon.LOCK, 22), span(null, "Covered"))
                cover.setAttribute("role", "button")
                cover.tabIndex = 0
                cover.on("click") { actions.reveal(m) }
                bubble.appendChild(cover)
            }
            m.isDeleted -> bubble.appendChild(div("deleted", "Message deleted"))
            else -> {
                if (m.att != null) bubble.appendChild(media ?: attachment(m, row, mine))
                if (m.body.isNotEmpty() && (m.kind == DexMessageKind.TEXT || m.att == null || m.kind == DexMessageKind.IMAGE || m.kind == DexMessageKind.VIDEO || m.kind == DexMessageKind.FILE)) {
                    bubble.appendChild(linkified(m.body))
                }
            }
        }
        bubble.appendChild(meta(m, mine, row))
        if (m.reactions.isNotEmpty() && !m.isDeleted) bubble.appendChild(reactions(m, row.meIp))
        node.appendChild(bubble)
        if (m.send == DexSendState.FAILED && mine) {
            node.appendChild(
                div("send-failed").add(
                    span(null, "Not sent"),
                    button("link-btn", "Retry sending", { actions.retrySend(m) }, text = "Retry"),
                    button("link-btn", "Cancel sending", { actions.cancelSend(m) }, text = "Cancel"),
                ),
            )
        } else if (mine && m.send != null) {
            node.appendChild(
                div("send-state", when (m.send) {
                    DexSendState.QUEUED -> "Queued"
                    DexSendState.SENDING -> "Sending…"
                    DexSendState.WAITING -> "Waiting for them"
                    DexSendState.FAILED -> ""
                }),
            )
        }
        if (!m.isDeleted && !(m.isCovered && !row.isRevealed)) {
            val tools = div("msg-tools").add(
                button("tool", "React", { actions.openReactions(if (row.isReactOpen) null else m.id) }, Icon.SMILE),
                button("tool", "Reply", { actions.reply(m) }, Icon.REPLY),
                button("tool", "More", { actions.openMenu(if (row.isMenuOpen) null else m.id) }, Icon.MORE),
            )
            node.appendChild(tools)
        }
        if (row.isReactOpen) node.appendChild(reactBar(m, row.meIp))
        if (row.isMenuOpen) node.appendChild(menu(m, mine))
        bubble.on("contextmenu") { e ->
            e.preventDefault()
            actions.openMenu(m.id)
        }
        var pressTimer: Int? = null
        bubble.on("touchstart") { pressTimer = window.setTimeout({ actions.openMenu(m.id) }, 500) }
        bubble.on("touchend") { pressTimer?.let(window::clearTimeout) }
        bubble.on("touchmove") { pressTimer?.let(window::clearTimeout) }
    }

    private fun meta(m: DexMessage, mine: Boolean, row: TimelineItem.Row): HTMLElement {
        val meta = div("meta")
        if (m.expiresAt != null) {
            val until = m.expiresAt
            meta.appendChild(span("expires").also { it.setAttribute("data-until", until.toString()); it.add(svg(Icon.HOURGLASS, 11), span(null, Format.remaining(until))) })
        }
        if (m.isEdited && !m.isDeleted) meta.appendChild(span("edited", "edited"))
        meta.appendChild(span("time", Format.clock(m.ts)))
        if (mine) {
            val tick = span("tick" + if (m.seenAt != null) " seen" else "")
            when {
                m.send == DexSendState.FAILED -> tick.textContent = "!"
                m.send != null || m.status == DexMessageStatus.PENDING -> tick.appendChild(svg(Icon.CLOCK, 13))
                m.status == DexMessageStatus.DELIVERED || m.status == DexMessageStatus.RECEIVED || m.seenAt != null -> tick.appendChild(svg(Icon.CHECK_CHECK, 13))
                else -> tick.appendChild(svg(Icon.CHECK, 13))
            }
            tick.setAttribute("aria-label", when {
                m.seenAt != null -> "Seen"
                m.status == DexMessageStatus.DELIVERED -> "Delivered"
                m.send != null -> "Sending"
                else -> "Sent"
            })
            meta.appendChild(tick)
        }
        return meta
    }

    private fun reactions(m: DexMessage, meIp: String): HTMLElement {
        val bar = div("reactions")
        val counts = LinkedHashMap<String, Int>()
        for ((_, e) in m.reactions) counts[e] = (counts[e] ?: 0) + 1
        val mine = m.reactions[meIp]
        for ((emoji, count) in counts) {
            val chip = button("reaction" + if (emoji == mine) " mine" else "", "React $emoji", { actions.react(m, emoji) }, text = if (count > 1) "$emoji $count" else emoji)
            bar.appendChild(chip)
        }
        return bar
    }

    private fun reactBar(m: DexMessage, meIp: String): HTMLElement {
        val bar = div("react-bar")
        for (e in quickReactions) bar.appendChild(button("emoji" + if (m.reactions[meIp] == e) " mine" else "", "React $e", { actions.react(m, e) }, text = e))
        bar.appendChild(button("emoji react-more", "More emoji", { actions.openDialog(Dialog.EmojiPick(EmojiTarget.React(m.id, reactPeer))) }, text = "＋"))
        return bar
    }

    private fun menu(m: DexMessage, mine: Boolean): HTMLElement {
        val menu = div("msg-menu").also { it.setAttribute("role", "menu") }
        fun item(icon: Icon, label: String, isDanger: Boolean = false, onClick: () -> Unit) {
            menu.appendChild(button("menu-item" + if (isDanger) " danger" else "", label, { onClick(); actions.openMenu(null) }, icon, label).also { it.setAttribute("role", "menuitem") })
        }
        item(Icon.REPLY, "Reply") { actions.reply(m) }
        if (m.body.isNotEmpty()) item(Icon.COPY, "Copy") { actions.copyMessage(m) }
        if (mine && m.kind == DexMessageKind.TEXT && !m.isCovered) item(Icon.PENCIL, "Edit") { actions.startEdit(m) }
        if (m.att != null && m.att.hasFile) {
            menu.appendChild(el("a", "menu-item") {
                setAttribute("href", Api.attachmentUrl(m.id))
                setAttribute("download", m.att.name)
                setAttribute("role", "menuitem")
                add(svg(Icon.DOWNLOAD), span(null, "Download"))
                on("click") { actions.openMenu(null) }
            })
        }
        item(Icon.TRASH, "Delete for me", isDanger = true) { actions.deleteMessage(m, forEveryone = false) }
        if (mine) item(Icon.TRASH, "Delete for everyone", isDanger = true) { actions.deleteMessage(m, forEveryone = true) }
        return menu
    }

    private fun linkified(body: String): HTMLElement {
        val text = div("text")
        val regex = Regex("""https?://[^\s<>"']+""")
        var last = 0
        for (match in regex.findAll(body)) {
            if (match.range.first > last) text.appendChild(span(null, body.substring(last, match.range.first)))
            val url = match.value.trimEnd('.', ',', ')', ';')
            text.appendChild(el("a", "link", url) { setAttribute("href", url); setAttribute("target", "_blank"); setAttribute("rel", "noopener noreferrer") })
            last = match.range.first + url.length
        }
        if (last < body.length) text.appendChild(span(null, body.substring(last)))
        return text
    }

    private fun attachment(m: DexMessage, row: TimelineItem.Row, mine: Boolean): HTMLElement {
        val att = m.att ?: return div("media")
        val node = div("media")
        val url = Api.attachmentUrl(m.id)
        when {
            m.status == DexMessageStatus.OFFERED && !mine -> {
                val canAfford = row.freeBytes <= 0 || att.size < row.freeBytes
                node.add(
                    div("offer").add(
                        svg(Icon.FILE, 22),
                        div("offer-text").add(div("file-name", att.name), div("file-size", Format.bytes(att.size))),
                    ),
                    div("offer-actions").add(
                        button("btn btn-primary", "Accept ${att.name}", { actions.acceptOffer(m) }, text = "Accept").also { (it as? org.w3c.dom.HTMLButtonElement)?.disabled = !canAfford },
                        button("btn", "Decline ${att.name}", { actions.declineOffer(m) }, text = "Decline"),
                    ),
                )
                if (!canAfford) node.appendChild(div("hint", "Not enough space on the phone"))
            }
            m.status == DexMessageStatus.RECEIVING -> {
                val pct = (m.transferPct ?: 0).coerceIn(0, 100)
                node.add(
                    div("offer").add(svg(Icon.FILE, 22), div("offer-text").add(div("file-name", att.name), div("file-size", "${Format.bytes(att.size)} · $pct%"))),
                    div("progress").add(div("progress-bar").also { it.style.width = "$pct%" }),
                    button("link-btn", "Cancel receiving ${att.name}", { actions.cancelTransfer(m) }, text = "Cancel"),
                )
            }
            m.status == DexMessageStatus.DECLINED || m.status == DexMessageStatus.CANCELLED -> {
                val what = when {
                    m.status == DexMessageStatus.DECLINED -> "Declined"
                    mine || m.isCancelledByMe -> "Cancelled"
                    else -> "Cancelled by ${row.peerLabel}"
                }
                node.appendChild(div("gone", "$what · ${att.name}"))
            }
            !att.hasFile -> node.appendChild(div("gone", "${att.name} · ${if (mine && m.send != null) "Sending…" else "Not available"}"))
            m.kind == DexMessageKind.IMAGE -> {
                val img = el("img", "photo") { setAttribute("src", url); setAttribute("alt", att.name); setAttribute("loading", "lazy") }
                if (att.width != null && att.height != null && att.width > 0 && att.height > 0) img.style.setProperty("aspect-ratio", "${att.width} / ${att.height}")
                img.on("click") { actions.openLightbox(m.id) }
                node.appendChild(img)
            }
            m.kind == DexMessageKind.VIDEO -> {
                node.appendChild(el("video", "video") { setAttribute("src", url); setAttribute("controls", ""); setAttribute("preload", "metadata"); setAttribute("playsinline", "") })
            }
            m.kind == DexMessageKind.VOICE || att.mime.startsWith("audio/") -> node.appendChild(voiceRow(url, att.durationMs))
            else -> {
                node.appendChild(
                    el("a", "file") {
                        setAttribute("href", url)
                        setAttribute("download", att.name)
                        setAttribute("target", "_blank")
                        setAttribute("rel", "noopener")
                        add(svg(Icon.FILE, 22), div("offer-text").add(div("file-name", att.name), div("file-size", Format.bytes(att.size))), svg(Icon.DOWNLOAD, 18))
                    },
                )
            }
        }
        return node
    }

    private fun voiceRow(url: String, durationMs: Long?): HTMLElement {
        val audio = el("audio") { setAttribute("src", url); setAttribute("preload", "metadata") } as HTMLAudioElement
        val time = span("voice-time", Format.duration(durationMs ?: 0))
        val bar = div("progress-bar")
        val play = button("icon-btn play", "Play voice message", {}, Icon.PLAY)
        play.on("click") {
            if (audio.paused) audio.play().catch { null } else audio.pause()
        }
        fun icon(isPlaying: Boolean) {
            play.clear()
            play.appendChild(svg(if (isPlaying) Icon.PAUSE else Icon.PLAY))
            play.setAttribute("aria-label", if (isPlaying) "Pause voice message" else "Play voice message")
        }
        audio.onplay = { icon(true) }
        audio.onpause = { icon(false) }
        audio.onended = {
            icon(false)
            bar.style.width = "0%"
            time.textContent = Format.duration(durationMs ?: (audio.duration * 1000).toLong())
        }
        audio.ontimeupdate = {
            val d = if (audio.duration.isFinite() && audio.duration > 0) audio.duration else (durationMs ?: 0L) / 1000.0
            if (d > 0) bar.style.width = "${(audio.currentTime / d * 100).coerceIn(0.0, 100.0)}%"
            time.textContent = Format.duration((audio.currentTime * 1000).toLong())
        }
        return div("voice").add(play, div("progress").add(bar), time, audio)
    }

    // --- composer -----------------------------------------------------------------------------

    private fun renderComposer(prev: AppState, next: AppState) {
        val c = next.composer
        if (prev.composer !== c || prev.openPeer != next.openPeer) {
            coverBtn.toggle("on", c.isCovered)
            coverBtn.setAttribute("aria-pressed", c.isCovered.toString())
            coverBtn.clear()
            coverBtn.appendChild(svg(if (c.isCovered) Icon.LOCK else Icon.UNLOCK))
            coverBtn.title = if (c.isCovered) "Cover message · on" else "Cover message"
            textarea.setAttribute("placeholder", if (c.isCovered) "Covered message" else if (c.editing != null) "Edit message" else "Message")
            emojiBar.toggle("hidden", !c.isEmojiOpen)
            if (c.isEmojiOpen) buildEmojiBar()
            emojiBtn.toggle("on", c.isEmojiOpen)
            replyStrip.clear()
            val quoted = c.editing ?: c.replyTo
            replyStrip.toggle("hidden", quoted == null)
            if (quoted != null) {
                val isEdit = c.editing != null
                replyStrip.add(
                    div("reply-body").add(
                        div("reply-title", if (isEdit) "Edit message" else if (quoted.dir == DexDirection.OUT) "Reply to you" else "Reply to ${next.view?.contact?.label ?: quoted.peer}"),
                        div("reply-snippet", if (quoted.isCovered) "Covered message" else quoted.body.ifEmpty { quoted.att?.name ?: "Attachment" }),
                    ),
                    button("icon-btn", "Cancel", { if (isEdit) actions.startEdit(null) else actions.reply(null) }, Icon.CLOSE),
                )
            }
            if (prev.composer.editing !== c.editing) {
                if (c.editing != null) {
                    textarea.value = c.editing.body
                    textarea.focus()
                } else if (prev.composer.editing != null) {
                    textarea.value = ""
                }
                autosize()
            }
            if (prev.composer.replyTo !== c.replyTo && c.replyTo != null) textarea.focus()
        }
        if (prev.uploads !== next.uploads) uploads.render(next.uploads) { it.id.toString() }
        if (prev.recording !== next.recording) renderRecording(next.recording)
    }

    private fun renderRecording(r: Recording?) {
        recordTimer?.let(window::clearInterval)
        recordTimer = null
        recordingBar.clear()
        recordingBar.toggle("hidden", r == null)
        composer.toggle("recording-on", r != null)
        when (r) {
            null -> Unit
            is Recording.Live -> {
                val time = span("rec-time", "00:00")
                recordingBar.add(
                    span("rec-dot"),
                    span("rec-label", "Recording"),
                    time,
                    button("icon-btn", "Discard recording", { actions.discardRecording() }, Icon.TRASH),
                    button("btn btn-primary", "Stop recording", { actions.stopRecording() }, Icon.STOP, "Stop"),
                )
                recordTimer = window.setInterval({ time.textContent = Format.duration(Date.now().toLong() - r.startedAt) }, 500)
            }
            is Recording.Stopped -> {
                recordingBar.add(
                    voiceRow(r.url, r.durationMs),
                    button("icon-btn", "Discard recording", { actions.discardRecording() }, Icon.TRASH),
                    button("btn btn-primary", "Send voice message", { actions.sendRecording() }, Icon.SEND, "Send"),
                )
            }
        }
    }

    private fun createUpload(u: Upload): HTMLElement = div("upload").also { updateUpload(it, u, u) }

    private fun updateUpload(node: HTMLElement, @Suppress("UNUSED_PARAMETER") previous: Upload, u: Upload) {
        node.clear()
        node.toggle("failed", u.error != null)
        node.add(
            svg(Icon.ATTACH, 16),
            div("upload-text").add(div("file-name", u.name), div("file-size", u.error ?: "${u.pct}%")),
            if (u.error == null) div("progress").add(div("progress-bar").also { it.style.width = "${u.pct}%" }) else null,
            if (u.error == null) button("icon-btn", "Cancel upload", { actions.cancelUpload(u.id) }, Icon.CLOSE) else button("icon-btn", "Dismiss", { actions.dismissUpload(u.id) }, Icon.CLOSE),
        )
    }
}

fun HTMLElement.closest(selector: String): HTMLElement? = asDynamic().closest(selector) as? HTMLElement

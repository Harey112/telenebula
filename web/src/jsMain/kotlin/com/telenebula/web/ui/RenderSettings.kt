package com.telenebula.web.ui

import com.telenebula.web.state.Actions
import com.telenebula.web.state.AppState
import com.telenebula.web.state.Dialog
import com.telenebula.web.state.SettingsTab
import com.telenebula.web.state.Tab
import com.telenebula.web.util.Format
import com.telenebula.web.wire.DexCallNotifications
import com.telenebula.web.wire.DexContactFlags
import com.telenebula.web.wire.DexDensity
import com.telenebula.web.wire.DexInAppNotifications
import com.telenebula.web.wire.DexLogLevel
import com.telenebula.web.wire.DexMessageNotifications
import com.telenebula.web.wire.DexNotifications
import com.telenebula.web.wire.DexPresencePrefs
import com.telenebula.web.wire.DexQuietHours
import com.telenebula.web.wire.DexRevealGate
import com.telenebula.web.wire.DexSettings
import com.telenebula.web.wire.DexSettingsPatch
import com.telenebula.web.wire.DexTextSize
import com.telenebula.web.wire.DexThemeMode
import kotlinx.browser.document
import kotlin.js.Date
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/** The phone's settings tree as one preferences window: categories left, the pane itself right. */
class SettingsView(root: HTMLElement, private val actions: Actions) {
    private val host = div("pane pane-settings hidden").also { root.appendChild(it) }
    private val categories = div("settings-cats").also { it.setAttribute("role", "tablist") }
    private val content = div("settings-content")
    private val catButtons = HashMap<SettingsTab, HTMLElement>()

    init {
        for (tab in SettingsTab.entries) {
            val b = el("button", "settings-cat") {
                setAttribute("type", "button")
                setAttribute("role", "tab")
            }
            b.add(svg(iconFor(tab), 18), span(null, tab.label))
            b.on("click") { actions.openSettingsTab(tab) }
            catButtons[tab] = b
            categories.add(b)
        }
        host.add(div("settings-side").add(div("pane-title", "Settings"), categories), content)
    }

    private fun iconFor(tab: SettingsTab): Icon = when (tab) {
        SettingsTab.ACCOUNT -> Icon.KEY
        SettingsTab.STATUS -> Icon.PING
        SettingsTab.APPEARANCE -> Icon.PALETTE
        SettingsTab.CHATS -> Icon.CHATS
        SettingsTab.NOTIFICATIONS -> Icon.BELL
        SettingsTab.PRIVACY -> Icon.SHIELD
        SettingsTab.CALLS -> Icon.CALL
        SettingsTab.NETWORK -> Icon.GLOBE
        SettingsTab.STORAGE -> Icon.DATABASE
        SettingsTab.DIAGNOSTICS -> Icon.ACTIVITY
        SettingsTab.UPDATES -> Icon.DOWNLOAD
        SettingsTab.DEX -> Icon.MONITOR
    }

    fun render(prev: AppState, next: AppState) {
        host.toggle("hidden", next.tab != Tab.SETTINGS)
        if (next.tab != Tab.SETTINGS) return
        if (prev.settingsTab != next.settingsTab) {
            for ((tab, b) in catButtons) {
                b.toggle("on", tab == next.settingsTab)
                b.setAttribute("aria-selected", (tab == next.settingsTab).toString())
            }
        }
        val changed = prev.tab != next.tab ||
            prev.settingsTab != next.settingsTab ||
            prev.settings != next.settings ||
            prev.account != next.account ||
            prev.network != next.network ||
            prev.storage != next.storage ||
            prev.diagnostics != next.diagnostics ||
            prev.updates != next.updates ||
            prev.contacts !== next.contacts
        if (!changed) return
        val focused = focusKey()
        content.clear()
        val s = next.settings
        if (s == null) {
            content.add(div("empty", "Waiting for the phone…"))
            return
        }
        content.add(div("pane-heading", next.settingsTab.label))
        when (next.settingsTab) {
            SettingsTab.ACCOUNT -> account(next)
            SettingsTab.STATUS -> status(s, next)
            SettingsTab.APPEARANCE -> appearance(s)
            SettingsTab.CHATS -> chats(s)
            SettingsTab.NOTIFICATIONS -> notifications(s)
            SettingsTab.PRIVACY -> privacy(s, next)
            SettingsTab.CALLS -> calls(s)
            SettingsTab.NETWORK -> network(s, next)
            SettingsTab.STORAGE -> storage(s, next)
            SettingsTab.DIAGNOSTICS -> diagnostics(next)
            SettingsTab.UPDATES -> updates(next)
            SettingsTab.DEX -> dex(s)
        }
        restoreFocus(focused)
    }

    /** A pane is rebuilt whole on every change from the phone, which would drop what is being typed. */
    private fun focusKey(): Pair<String, Int?>? {
        val active = document.activeElement as? HTMLElement ?: return null
        if (!content.contains(active)) return null
        val key = active.getAttribute("aria-label") ?: return null
        return key to (active as? HTMLInputElement)?.selectionStart
    }

    private fun restoreFocus(saved: Pair<String, Int?>?) {
        val (key, caret) = saved ?: return
        val nodes = content.querySelectorAll("[aria-label]")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? HTMLElement ?: continue
            if (node.getAttribute("aria-label") != key) continue
            node.focus()
            val input = node as? HTMLInputElement
            if (caret != null && input != null && input.type != "checkbox" && input.type != "color") {
                runCatching { input.setSelectionRange(caret, caret) }
            }
            return
        }
    }

    private fun patch(p: DexSettingsPatch) = actions.patchSettings(p)

    // --- panes ------------------------------------------------------------------------------

    private fun account(state: AppState) {
        val a = state.account
        if (a == null) {
            content.add(div("empty", "Waiting for the phone…"))
            return
        }
        content.add(
            section("Identity") {
                add(infoRow("Node ID (certificate name)", if (a.certName.isEmpty()) "" else "@${a.certName}"))
                add(infoRow("Overlay address", a.overlayIp, isMono = true))
                add(infoRow("Overlay networks", a.networks.joinToString(", "), isMono = true, isWrapped = true))
            },
            section("Certificate", "Renewing the certificate, and setting this phone up, happen on the phone. This is a browser: it never holds the key.") {
                add(infoRow("Status", a.certStatus))
                add(infoRow("Valid until", a.certNotAfter))
                add(infoRow("Fingerprint", a.certFingerprint, isMono = true, isWrapped = true))
            },
            section("Build") {
                add(infoRow("App version", a.appVersion))
                add(infoRow("Core version", a.coreVersion))
                add(infoRow("Ports", if (a.listenPort == 0) "" else "listen ${a.listenPort} · messages ${a.msgPort}", isMono = true))
                add(infoRow("Tunnel MTU", if (a.mtu == 0) "" else a.mtu.toString()))
            },
        )
    }

    private fun appearance(s: DexSettings) {
        content.add(
            section("Theme", "The phone and this browser follow the same choice.") {
                add(
                    selectRow(
                        "Appearance",
                        null,
                        listOf(Choice("SYSTEM", "Follow the system"), Choice("LIGHT", "Light"), Choice("DARK", "Dark")),
                        s.themeMode.name,
                    ) { key -> patch(DexSettingsPatch(themeMode = DexThemeMode.valueOf(key))) },
                )
                add(
                    selectRow("Colour theme", null, THEMES, s.colorTheme) { key ->
                        patch(DexSettingsPatch(colorTheme = key))
                    },
                )
                if (s.colorTheme == "custom") {
                    add(colorRow("Custom accent", "Used when the colour theme is Custom", s.customAccent) { hex -> patch(DexSettingsPatch(customAccent = hex, colorTheme = "custom")) })
                } else {
                    add(actionRow("Custom accent", "Pick your own accent colour", button = "Use custom") { patch(DexSettingsPatch(colorTheme = "custom")) })
                }
            },
            section("Messages") {
                add(textSizeRow(s))
                add(densityRow(s))
            },
        )
    }

    private fun textSizeRow(s: DexSettings): HTMLElement = selectRow(
        "Text size",
        null,
        listOf(Choice("SMALL", "Small"), Choice("MEDIUM", "Medium"), Choice("LARGE", "Large")),
        s.chatTextSize.name,
    ) { key -> patch(DexSettingsPatch(chatTextSize = DexTextSize.valueOf(key))) }

    private fun densityRow(s: DexSettings): HTMLElement = selectRow(
        "Message density",
        null,
        listOf(Choice("COMFORTABLE", "Comfortable"), Choice("COMPACT", "Compact")),
        s.messageDensity.name,
    ) { key -> patch(DexSettingsPatch(messageDensity = DexDensity.valueOf(key))) }

    private fun chats(s: DexSettings) {
        content.add(
            section("Composing") {
                add(textSizeRow(s))
                add(densityRow(s))
                add(switchRow("Enter sends the message", "Otherwise Enter starts a new line and Ctrl+Enter sends", s.isEnterToSend) { v -> patch(DexSettingsPatch(isEnterToSend = v)) })
            },
        )
        val quick = div("rows")
        val slots = s.quickReactions
        for (i in 0 until 6) {
            val emoji = slots.getOrNull(i) ?: "·"
            quick.add(
                actionRow("Slot ${i + 1}", emoji, button = "Change") {
                    actions.openDialog(Dialog.QuickReaction(i, slots.getOrNull(i).orEmpty()))
                },
            )
        }
        val s2 = div("section").add(div("section-title", "Quick reactions"), quick, div("section-note", "The six offered first when you react to a message."))
        content.add(s2)
    }

    private fun notifications(s: DexSettings) {
        val n = s.notifications
        val m = n.messages
        fun setNotifications(next: DexNotifications) = patch(DexSettingsPatch(notifications = next))
        content.add(
            section("Messages", "The phone plays the sound and does the vibrating; this browser only sets what it does.") {
                add(switchRow("Message notifications", null, m.enabled) { v -> setNotifications(n.copy(messages = m.copy(enabled = v))) })
                add(switchRow("Show who sent it", null, m.showSender, isEnabled = m.enabled) { v -> setNotifications(n.copy(messages = m.copy(showSender = v))) })
                add(switchRow("Show a preview", null, m.preview, isEnabled = m.enabled) { v -> setNotifications(n.copy(messages = m.copy(preview = v))) })
                add(switchRow("Sound", null, m.sound, isEnabled = m.enabled) { v -> setNotifications(n.copy(messages = m.copy(sound = v))) })
                add(switchRow("Vibrate", null, m.vibrate, isEnabled = m.enabled) { v -> setNotifications(n.copy(messages = m.copy(vibrate = v))) })
                add(switchRow("Pop up on screen", null, m.popup, isEnabled = m.enabled) { v -> setNotifications(n.copy(messages = m.copy(popup = v))) })
                add(switchRow("Announce reactions", null, m.reactions, isEnabled = m.enabled) { v -> setNotifications(n.copy(messages = m.copy(reactions = v))) })
            },
            section("Calls") {
                add(switchRow("Ring for calls", null, n.calls.ring) { v -> setNotifications(n.copy(calls = n.calls.copy(ring = v))) })
                add(switchRow("Vibrate while ringing", null, n.calls.vibrate, isEnabled = n.calls.ring) { v -> setNotifications(n.copy(calls = n.calls.copy(vibrate = v))) })
                add(switchRow("Missed call notification", null, n.calls.missedNotification) { v -> setNotifications(n.copy(calls = n.calls.copy(missedNotification = v))) })
            },
            section("In the app") {
                add(switchRow("Vibrate for the chat you are reading", null, n.inApp.vibrate) { v -> setNotifications(n.copy(inApp = DexInAppNotifications(vibrate = v))) })
            },
            section("Quiet hours", "While quiet hours are on, the phone stays silent; messages still arrive.") {
                add(switchRow("Quiet hours", null, n.quietHours.enabled) { v -> setNotifications(n.copy(quietHours = n.quietHours.copy(enabled = v))) })
                add(timeRow("From", n.quietHours.fromHour, n.quietHours.fromMinute, isEnabled = n.quietHours.enabled) { h, mi -> setNotifications(n.copy(quietHours = n.quietHours.copy(fromHour = h, fromMinute = mi))) })
                add(timeRow("Until", n.quietHours.toHour, n.quietHours.toMinute, isEnabled = n.quietHours.enabled) { h, mi -> setNotifications(n.copy(quietHours = n.quietHours.copy(toHour = h, toMinute = mi))) })
            },
            section(null, null) {
                add(actionRow("Reset notifications", "Put every notification setting back to its default", button = "Reset") { setNotifications(DexNotifications(DexMessageNotifications(), DexCallNotifications(), DexInAppNotifications(), DexQuietHours())) })
            },
        )
    }

    private fun privacy(s: DexSettings, state: AppState) {
        val blocked = state.contacts.values.filter { it.isBlocked }.sortedBy { it.label.lowercase() }
        content.add(
            section("What your contacts see") {
                add(switchRow("Send read receipts", "They see when you have read their message", s.sendReadReceipts) { v -> patch(DexSettingsPatch(sendReadReceipts = v)) })
                add(switchRow("Send typing indicators", "They see when you are writing", s.sendTypingIndicators) { v -> patch(DexSettingsPatch(sendTypingIndicators = v)) })
            },
            section("On this phone", "The app lock is the phone's own, so it is set there.") {
                add(switchRow("Block screenshots", null, s.isScreenshotBlocked) { v -> patch(DexSettingsPatch(isScreenshotBlocked = v)) })
                add(infoRow("App lock", if (s.isAppLockEnabled) "On" else "Off"))
                add(
                    selectRow(
                        "Lock after",
                        null,
                        listOf(Choice("0", "Immediately"), Choice("30", "30 seconds"), Choice("60", "1 minute"), Choice("300", "5 minutes"), Choice("900", "15 minutes")),
                        s.appLockAfterSec.toString(),
                        isEnabled = s.isAppLockEnabled,
                    ) { key -> key.toIntOrNull()?.let { patch(DexSettingsPatch(appLockAfterSec = it)) } },
                )
            },
            section("Covered messages", "How a covered message is opened. A code or the phone's lock can only be answered on the phone.") {
                add(
                    selectRow(
                        "Reveal with",
                        null,
                        listOf(Choice("TAP", "Just tap"), Choice("ASK", "Ask first"), Choice("CODE", "A code"), Choice("DEVICE", "The phone's lock")),
                        s.coverRevealGate.name,
                    ) { key -> patch(DexSettingsPatch(coverRevealGate = DexRevealGate.valueOf(key))) },
                )
            },
        )
        val rows = div("rows")
        if (blocked.isEmpty()) {
            rows.add(noteRow("Nobody is blocked."))
        } else {
            for (c in blocked) rows.add(actionRow(c.label, c.ip, button = "Unblock") { actions.setContactFlags(c.ip, DexContactFlags(isBlocked = false)) })
        }
        content.add(div("section").add(div("section-title", "Blocked (${blocked.size})"), rows))
    }

    /** Presence, as the phone's Status screen states it: sharing, a pause, and what that looks like. */
    private fun status(s: DexSettings, state: AppState) {
        val p = s.presence
        val now = Date.now().toLong()
        val isPaused = p.isShared && p.pausedUntil > now
        val isActive = p.isShared && (p.pausedUntil == 0L || now >= p.pausedUntil)
        val isTunnelOn = state.network?.isTunnelOn == true
        content.add(
            section("Status", "What a contact sees when they check on you.") {
                add(
                    switchRow("Share when I am online", null, isActive) { _ ->
                        // off while paused means end the pause, not stop sharing
                        val next = if (isPaused) DexPresencePrefs(isShared = true) else DexPresencePrefs(isShared = !p.isShared)
                        patch(DexSettingsPatch(presence = next))
                    },
                )
                add(
                    selectRow("Pause sharing", null, PAUSES, if (isPaused) p.pauseMinutes.toString() else "0", isEnabled = p.isShared) { key ->
                        val minutes = key.toIntOrNull() ?: 0
                        val until = if (minutes > 0) now + minutes * 60_000L else 0L
                        patch(DexSettingsPatch(presence = DexPresencePrefs(isShared = true, pauseMinutes = minutes, pausedUntil = until)))
                    },
                )
                if (isPaused) add(noteRow("Paused until ${Format.clock(p.pausedUntil)}; until then contacts see “reachable”."))
                add(
                    infoRow(
                        "Contacts see",
                        when {
                            !isTunnelOn -> "Offline — the tunnel is off"
                            isActive -> "Online while the app is open, reachable otherwise"
                            else -> "Reachable"
                        },
                    ),
                )
            },
        )
    }

    private fun calls(s: DexSettings) {
        content.add(
            section("Calls on the phone", "A call this browser holds always uses the machine's own speakers.") {
                add(switchRow("Video calls start on speaker", null, s.isVideoSpeakerDefault) { v -> patch(DexSettingsPatch(isVideoSpeakerDefault = v)) })
            },
        )
    }

    private fun network(s: DexSettings, state: AppState) {
        val n = state.network
        val a = state.account
        content.add(
            section("Tunnel", "Turning the tunnel on needs the phone's permission the first time; until it is given, the phone has to do it.") {
                add(switchRow("Nebula tunnel", if (n?.isTunnelOn == true) "Connected" else "Disconnected", n?.isTunnelOn ?: false) { v -> actions.setTunnel(v) })
                add(infoRow("Tunnel uptime", if (n == null || !n.isTunnelOn) "" else Format.duration(n.tunnelUptimeMs)))
                add(infoRow("Messaging engine uptime", if (n == null) "" else Format.duration(n.engineUptimeMs)))
                add(infoRow("Peers with a live link", n?.connectedCount?.toString().orEmpty()))
            },
        )
        if (a != null) {
            content.add(
                section("Lighthouse") {
                    add(infoRow("Address", a.lighthouseIp, isMono = true))
                    add(infoRow("Underlay", a.lighthouseUnderlay, isMono = true))
                    add(infoRow("Status", n?.lighthouseStatus.orEmpty()))
                },
            )
        }
        val peerRows = div("rows")
        val peers = n?.peers.orEmpty()
        if (peers.isEmpty()) {
            peerRows.add(noteRow(if (n?.isTunnelOn == true) "No tunnels established yet." else "The tunnel is off."))
        } else {
            for (p in peers) {
                peerRows.add(
                    infoRow(
                        p.label,
                        listOfNotNull(
                            if (p.isConnected) "connected" else "idle",
                            p.endpoint.ifEmpty { null },
                            p.latencyMs?.let { "${it} ms" },
                        ).joinToString(" · "),
                    ),
                )
            }
        }
        content.add(div("section").add(div("section-title", "Peers (${peers.size})"), peerRows))
        content.add(
            section("Data") {
                add(infoRow("Over the message link", if (n == null) "" else "↑ ${Format.bytes(n.bytesSent)}   ↓ ${Format.bytes(n.bytesReceived)}", isMono = true))
                add(infoRow("Outbox", if (n == null) "" else "${n.pendingActions} pending · ${n.failedActions} failed"))
            },
            section("Advanced") {
                add(switchRow("Start when the phone starts", "Also after an update; the tunnel comes up on its own", s.isStartOnBootEnabled) { v -> patch(DexSettingsPatch(isStartOnBootEnabled = v)) })
                add(switchRow("Keep the connection in the background", null, s.isBackgroundConnectionEnabled) { v -> patch(DexSettingsPatch(isBackgroundConnectionEnabled = v)) })
                add(
                    selectRow("Nebula log level", null, listOf(Choice("INFO", "Info"), Choice("DEBUG", "Debug")), s.nebulaLogLevel.name) { key ->
                        patch(DexSettingsPatch(nebulaLogLevel = DexLogLevel.valueOf(key)))
                    },
                )
                add(switchRow("Developer mode", null, s.isDeveloperMode) { v -> patch(DexSettingsPatch(isDeveloperMode = v)) })
            },
        )
    }

    private fun storage(s: DexSettings, state: AppState) {
        val st = state.storage
        if (st == null) {
            content.add(div("empty", "Waiting for the phone…"))
            return
        }
        content.add(statGrid("Messages" to st.messages.toString(), "Contacts" to st.contacts.toString(), "Attachments" to st.attachmentsCount.toString(), "Free space" to Format.bytes(st.freeBytes)))
        content.add(
            section("On the phone") {
                add(infoRow("Database", Format.bytes(st.dbBytes)))
                add(infoRow("Attachments", "${Format.bytes(st.attachmentsBytes)} · ${st.attachmentsCount} files"))
                add(infoRow("Partial transfers", "${Format.bytes(st.partialBytes)} · ${st.partialCount} files"))
            },
            section("Tidying up") {
                add(infoRow("Media no message refers to", "${Format.bytes(st.orphanBytes)} · ${st.orphanCount} files"))
                add(actionRow("Clean up", "Remove media no message refers to", button = "Clean up", isEnabled = st.orphanCount > 0) { actions.clearOrphans() })
                add(switchRow("Clean up automatically", "Every time the app starts", s.autoCleanOrphans) { v -> patch(DexSettingsPatch(autoCleanOrphans = v)) })
            },
            section("Danger", "This cannot be undone, and it happens on the phone.") {
                add(actionRow("Clear all history", "Every message in every chat", button = "Clear", isDanger = true) { actions.clearAllHistory() })
            },
        )
    }

    private fun diagnostics(state: AppState) {
        val d = state.diagnostics
        if (d == null) {
            content.add(div("empty", "Waiting for the phone…"))
            return
        }
        content.add(statGrid("Queued" to d.queuedCount.toString(), "Peers waiting" to d.queuedPeers.toString(), "Failed" to d.failedCount.toString()))
        content.add(
            section("Outbox") {
                add(infoRow("Lighthouse", d.lighthouseStatus))
                add(actionRow("Retry everything that failed", null, button = "Retry", isEnabled = d.failedCount > 0) { actions.retryFailed("") })
            },
        )
        if (d.callTrail.isNotEmpty()) {
            val trail = div("rows log")
            for (line in d.callTrail) trail.add(div("log-line", line))
            content.add(div("section").add(div("section-title", "Last call"), trail))
        }
        val log = div("log log-tail")
        log.textContent = d.logTail.ifEmpty { "Nothing logged yet." }
        content.add(div("section").add(div("section-title", "Nebula log"), log))
    }

    private fun updates(state: AppState) {
        val u = state.updates
        if (u == null) {
            content.add(div("empty", "Waiting for the phone…"))
            return
        }
        content.add(
            section("Version", "The phone downloads and installs an update; a browser cannot.") {
                add(infoRow("Installed", u.appVersion))
                add(infoRow("Latest", u.latestVersion ?: "Not checked yet"))
                add(infoRow("Status", if (u.isUpdateAvailable) "An update is available" else "Up to date"))
                add(infoRow("Last checked", if (u.lastCheckedAt == 0L) "Never" else Format.listTime(u.lastCheckedAt) + " at " + Format.clock(u.lastCheckedAt)))
                u.lastError?.let { add(infoRow("Last error", it, isWrapped = true)) }
            },
            section("Checking") {
                add(switchRow("Check daily", null, u.isDailyCheckEnabled) { v -> patch(DexSettingsPatch(isDailyUpdateCheckEnabled = v)) })
                add(actionRow("Check now", null, button = "Check") { actions.checkUpdates() })
            },
        )
    }

    private fun dex(s: DexSettings) {
        content.add(
            section("This browser's way in", "Dex is set up on the phone. A browser Dex is serving does not get to change the lock on its own door.") {
                add(infoRow("Username", s.dexUsername))
                add(infoRow("Client limit", s.dexMaxClients.toString()))
                add(infoRow("Port", if (s.dexPort == 0) "" else s.dexPort.toString(), isMono = true))
            },
        )
    }

    private companion object {
        /** the phone's own Status screen offers exactly these */
        val PAUSES = listOf(Choice("0", "Off"), Choice("30", "30 minutes"), Choice("60", "1 hour"), Choice("480", "8 hours"), Choice("1440", "24 hours"))

        val THEMES = listOf(
            Choice("sky", "Sky"),
            Choice("forest", "Forest"),
            Choice("amber", "Amber"),
            Choice("rose", "Rose"),
            Choice("violet", "Violet"),
            Choice("slate", "Slate"),
            Choice("custom", "Custom"),
        )
    }
}

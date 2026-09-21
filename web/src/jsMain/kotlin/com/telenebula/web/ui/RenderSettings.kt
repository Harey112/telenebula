package com.telenebula.web.ui

import com.telenebula.web.state.Actions
import com.telenebula.web.state.AppState
import com.telenebula.web.state.Dialog
import com.telenebula.web.state.Effective
import com.telenebula.web.state.EmojiTarget
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
import com.telenebula.web.wire.DexSurface
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
            prev.emoji.size != next.emoji.size ||
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
            SettingsTab.APPEARANCE -> appearance(s, next.effective)
            SettingsTab.CHATS -> chats(s)
            SettingsTab.NOTIFICATIONS -> notifications(s, next.effective)
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
                add(infoRow("Node ID (certificate name)", if (a.certName.isEmpty()) "" else "@${a.certName}", where = Where.PHONE))
                add(infoRow("Overlay address", a.overlayIp, isMono = true))
                add(infoRow("Overlay networks", a.networks.joinToString(", "), isMono = true, isWrapped = true))
            },
            section("Certificate", "Renewing the certificate, and setting this phone up, happen on the phone. This is a browser: it never holds the key.") {
                add(infoRow("Status", a.certStatus))
                add(infoRow("Valid until", a.certNotAfter))
                add(infoRow("Fingerprint", a.certFingerprint, isMono = true, isWrapped = true))
            },
            section("Only on the phone", "Renewing the certificate, resetting the identity, and the About screen's links all need the handset.") {
                add(infoRow("Renew certificate", "On the phone", where = Where.PHONE))
                add(infoRow("Reset identity", "On the phone", where = Where.PHONE))
                add(infoRow("Documentation and reporting a problem", "On the phone, under About", where = Where.PHONE))
            },
            section("Build") {
                add(infoRow("App version", a.appVersion))
                add(infoRow("Core version", a.coreVersion))
                add(infoRow("Ports", if (a.listenPort == 0) "" else "listen ${a.listenPort} · messages ${a.msgPort}", isMono = true))
                add(infoRow("Tunnel MTU", if (a.mtu == 0) "" else a.mtu.toString()))
            },
        )
    }

    /** What the browser sets for itself is stored on the phone beside the app's, so both are here. */
    private fun surface(s: DexSettings, change: DexSurface.() -> DexSurface) =
        patch(DexSettingsPatch(dexSurface = s.dexSurface.change()))

    private fun labelOf(options: List<Choice>, key: String): String = options.firstOrNull { it.key == key }?.label ?: key

    private fun appearance(s: DexSettings, eff: Effective) {
        val d = s.dexSurface
        content.add(
            section("Theme", "Each row is the phone's. Leave the browser's own line on Follow app to keep the two the same.") {
                add(
                    withSurface(
                        selectRow("Appearance", null, THEME_MODES, s.themeMode.name, where = Where.BOTH) { k -> patch(DexSettingsPatch(themeMode = DexThemeMode.valueOf(k))) },
                        followSelect("Appearance", labelOf(THEME_MODES, s.themeMode.name), THEME_MODES, d.themeMode?.name) { k ->
                            surface(s) { copy(themeMode = k?.let { DexThemeMode.valueOf(it) }) }
                        },
                    ),
                )
                add(
                    withSurface(
                        selectRow("Colour theme", null, THEMES, s.colorTheme, where = Where.BOTH) { k -> patch(DexSettingsPatch(colorTheme = k)) },
                        followSelect("Colour theme", labelOf(THEMES, s.colorTheme), THEMES, d.colorTheme) { k -> surface(s) { copy(colorTheme = k) } },
                    ),
                )
                add(
                    withSurface(
                        colorRow("Custom accent", "Used when the colour theme is Custom", s.customAccent, where = Where.BOTH) { hex ->
                            patch(DexSettingsPatch(customAccent = hex, colorTheme = "custom"))
                        },
                        accentControl(s, eff),
                    ),
                )
            },
            section("Messages") {
                add(textSizeRow(s))
                add(densityRow(s))
            },
        )
    }

    /** A colour input cannot say "follow the app", so the choice leads and the colour follows it. */
    private fun accentControl(s: DexSettings, eff: Effective): HTMLElement {
        val own = s.dexSurface.customAccent
        val cell = div("cell-pair")
        cell.add(
            followSelect("Custom accent", s.customAccent, listOf(Choice("own", "Own colour")), own?.let { "own" }) { k ->
                surface(s) { copy(customAccent = if (k == null) null else eff.customAccent) }
            },
        )
        if (own != null) cell.add(colorControl("Custom accent, this browser", own) { hex -> surface(s) { copy(customAccent = hex) } })
        return cell
    }

    private fun textSizeRow(s: DexSettings): HTMLElement = withSurface(
        selectRow("Text size", null, TEXT_SIZES, s.chatTextSize.name, where = Where.BOTH) { k -> patch(DexSettingsPatch(chatTextSize = DexTextSize.valueOf(k))) },
        followSelect("Text size", labelOf(TEXT_SIZES, s.chatTextSize.name), TEXT_SIZES, s.dexSurface.chatTextSize?.name) { k ->
            surface(s) { copy(chatTextSize = k?.let { DexTextSize.valueOf(it) }) }
        },
    )

    private fun densityRow(s: DexSettings): HTMLElement = withSurface(
        selectRow("Message density", null, DENSITIES, s.messageDensity.name, where = Where.BOTH) { k -> patch(DexSettingsPatch(messageDensity = DexDensity.valueOf(k))) },
        followSelect("Message density", labelOf(DENSITIES, s.messageDensity.name), DENSITIES, s.dexSurface.messageDensity?.name) { k ->
            surface(s) { copy(messageDensity = k?.let { DexDensity.valueOf(it) }) }
        },
    )

    private fun chats(s: DexSettings) {
        content.add(
            section("Composing") {
                add(textSizeRow(s))
                add(densityRow(s))
                add(
                    withSurface(
                        switchRow("Enter sends the message", "Otherwise Enter starts a new line and Shift+Enter sends", s.isEnterToSend, where = Where.BOTH) { v ->
                            patch(DexSettingsPatch(isEnterToSend = v))
                        },
                        followSwitch("Enter sends the message", s.isEnterToSend, s.dexSurface.isEnterToSend) { v -> surface(s) { copy(isEnterToSend = v) } },
                    ),
                )
            },
        )
        val quick = div("rows")
        val slots = s.quickReactions
        for (i in 0 until 6) {
            val emoji = slots.getOrNull(i) ?: "·"
            quick.add(
                actionRow("Slot ${i + 1}", emoji, button = "Change", where = Where.BOTH) {
                    actions.openDialog(Dialog.EmojiPick(EmojiTarget.Slot(i)))
                },
            )
        }
        content.add(div("section").add(div("section-title", "Quick reactions"), quick, div("section-note", "The six offered first when you react to a message, on the phone and here.")))
    }

    private fun notifications(s: DexSettings, eff: Effective) {
        val n = s.notifications
        val m = n.messages
        val d = s.dexSurface
        fun setNotifications(next: DexNotifications) = patch(DexSettingsPatch(notifications = next))
        content.add(
            section("Messages", "The phone rings and vibrates; this browser shows a desktop notification. The three below can differ.") {
                add(
                    withSurface(
                        switchRow("Message notifications", null, m.enabled, where = Where.BOTH) { v -> setNotifications(n.copy(messages = m.copy(enabled = v))) },
                        followSwitch("Message notifications", m.enabled, d.notificationsEnabled) { v -> surface(s) { copy(notificationsEnabled = v) } },
                    ),
                )
                add(
                    withSurface(
                        switchRow("Show a preview", "Put the message itself in the notification", m.preview, isEnabled = m.enabled, where = Where.BOTH) { v ->
                            setNotifications(n.copy(messages = m.copy(preview = v)))
                        },
                        followSwitch("Show a preview", m.preview, d.notificationPreview, isEnabled = eff.notificationsEnabled) { v -> surface(s) { copy(notificationPreview = v) } },
                    ),
                )
                add(
                    withSurface(
                        switchRow("Sound", "The phone plays its tone; the browser plays a short beep", m.sound, isEnabled = m.enabled, where = Where.BOTH) { v ->
                            setNotifications(n.copy(messages = m.copy(sound = v)))
                        },
                        followSwitch("Sound", m.sound, d.notificationSound, isEnabled = eff.notificationsEnabled) { v -> surface(s) { copy(notificationSound = v) } },
                    ),
                )
                add(switchRow("Show who sent it", null, m.showSender, isEnabled = m.enabled, where = Where.PHONE) { v -> setNotifications(n.copy(messages = m.copy(showSender = v))) })
                add(switchRow("Vibrate", null, m.vibrate, isEnabled = m.enabled, where = Where.PHONE) { v -> setNotifications(n.copy(messages = m.copy(vibrate = v))) })
                add(switchRow("Pop up on screen", null, m.popup, isEnabled = m.enabled, where = Where.PHONE) { v -> setNotifications(n.copy(messages = m.copy(popup = v))) })
                add(switchRow("Announce reactions", null, m.reactions, isEnabled = m.enabled, where = Where.PHONE) { v -> setNotifications(n.copy(messages = m.copy(reactions = v))) })
            },
            section("Calls") {
                add(switchRow("Ring for calls", null, n.calls.ring, where = Where.PHONE) { v -> setNotifications(n.copy(calls = n.calls.copy(ring = v))) })
                add(switchRow("Vibrate while ringing", null, n.calls.vibrate, isEnabled = n.calls.ring, where = Where.PHONE) { v -> setNotifications(n.copy(calls = n.calls.copy(vibrate = v))) })
                add(switchRow("Missed call notification", null, n.calls.missedNotification, where = Where.PHONE) { v -> setNotifications(n.copy(calls = n.calls.copy(missedNotification = v))) })
            },
            section("In the app") {
                add(switchRow("Vibrate for the chat you are reading", null, n.inApp.vibrate, where = Where.PHONE) { v -> setNotifications(n.copy(inApp = DexInAppNotifications(vibrate = v))) })
            },
            section("Quiet hours", "While quiet hours are on, the phone stays silent; messages still arrive.") {
                add(switchRow("Quiet hours", null, n.quietHours.enabled, where = Where.PHONE) { v -> setNotifications(n.copy(quietHours = n.quietHours.copy(enabled = v))) })
                add(timeRow("From", n.quietHours.fromHour, n.quietHours.fromMinute, isEnabled = n.quietHours.enabled, where = Where.PHONE) { h, mi -> setNotifications(n.copy(quietHours = n.quietHours.copy(fromHour = h, fromMinute = mi))) })
                add(timeRow("Until", n.quietHours.toHour, n.quietHours.toMinute, isEnabled = n.quietHours.enabled, where = Where.PHONE) { h, mi -> setNotifications(n.copy(quietHours = n.quietHours.copy(toHour = h, toMinute = mi))) })
            },
            section("Only on the phone", "Android's own notification screens, for the tunnel's channel and for the app, open on the handset.") {
                add(infoRow("Tunnel notification settings", "On the phone", where = Where.PHONE))
                add(infoRow("Android notification settings", "On the phone", where = Where.PHONE))
            },
            section(null, null) {
                add(actionRow("Reset notifications", "Put every notification setting back to its default", button = "Reset", where = Where.PHONE) { setNotifications(DexNotifications(DexMessageNotifications(), DexCallNotifications(), DexInAppNotifications(), DexQuietHours())) })
            },
        )
    }

    private fun privacy(s: DexSettings, state: AppState) {
        val isPhoneOnlyGate = s.coverRevealGate == DexRevealGate.CODE || s.coverRevealGate == DexRevealGate.DEVICE
        val blocked = state.contacts.values.filter { it.isBlocked }.sortedBy { it.label.lowercase() }
        content.add(
            section("What your contacts see") {
                add(switchRow("Send read receipts", "They see when you have read their message", s.sendReadReceipts, where = Where.BOTH) { v -> patch(DexSettingsPatch(sendReadReceipts = v)) })
                add(switchRow("Send typing indicators", "They see when you are writing", s.sendTypingIndicators, where = Where.BOTH) { v -> patch(DexSettingsPatch(sendTypingIndicators = v)) })
            },
            section("On this phone", "The app lock is the phone's own, so it is set there.") {
                add(switchRow("Block screenshots", "A browser cannot stop a screenshot; this is the phone\u2019s own window flag", s.isScreenshotBlocked, where = Where.PHONE) { v -> patch(DexSettingsPatch(isScreenshotBlocked = v)) })
                add(infoRow("App lock", if (s.isAppLockEnabled) "On" else "Off", where = Where.PHONE))
                add(
                    selectRow(
                        "Lock after",
                        null,
                        listOf(Choice("0", "Immediately"), Choice("30", "30 seconds"), Choice("60", "1 minute"), Choice("300", "5 minutes"), Choice("900", "15 minutes")),
                        s.appLockAfterSec.toString(),
                        isEnabled = s.isAppLockEnabled,
                        where = Where.PHONE,
                    ) { key -> key.toIntOrNull()?.let { patch(DexSettingsPatch(appLockAfterSec = it)) } },
                )
            },
            section(
                "Covered messages",
                if (isPhoneOnlyGate) {
                    "A code and the phone's lock are both answered on the handset, so while one of them is chosen a covered message cannot be opened in this browser, and its attachment is not sent here."
                } else {
                    "How a covered message is opened. Tap and Ask work here; a code and the phone's lock are answered on the handset."
                },
            ) {
                add(
                    selectRow(
                        "Reveal with",
                        null,
                        listOf(
                            Choice("TAP", "Just tap — phone and browser"),
                            Choice("ASK", "Ask first — phone and browser"),
                            Choice("CODE", "A code — phone only"),
                            Choice("DEVICE", "The phone's lock — phone only"),
                        ),
                        s.coverRevealGate.name,
                        where = if (isPhoneOnlyGate) Where.PHONE else Where.BOTH,
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
                    switchRow("Share when I am online", null, isActive, where = Where.BOTH) { _ ->
                        // off while paused means end the pause, not stop sharing
                        val next = if (isPaused) DexPresencePrefs(isShared = true) else DexPresencePrefs(isShared = !p.isShared)
                        patch(DexSettingsPatch(presence = next))
                    },
                )
                add(
                    selectRow("Pause sharing", null, PAUSES, if (isPaused) p.pauseMinutes.toString() else "0", isEnabled = p.isShared, where = Where.BOTH) { key ->
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
                add(switchRow("Video calls start on speaker", "A call this browser holds uses the machine\u2019s own output either way", s.isVideoSpeakerDefault, where = Where.PHONE) { v -> patch(DexSettingsPatch(isVideoSpeakerDefault = v)) })
            },
        )
    }

    private fun network(s: DexSettings, state: AppState) {
        val n = state.network
        val a = state.account
        content.add(
            section("Tunnel", "Turning the tunnel on needs the phone's permission the first time; until it is given, the phone has to do it.") {
                add(switchRow("Nebula tunnel", if (n?.isTunnelOn == true) "Connected" else "Disconnected", n?.isTunnelOn ?: false, where = Where.PHONE) { v -> actions.setTunnel(v) })
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
                    add(infoRow("Change the lighthouse", "On the phone", where = Where.PHONE))
                    add(infoRow("Reconnect the tunnel", "On the phone; switching it off and on here does the same", where = Where.PHONE))
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
                add(switchRow("Start when the phone starts", "Also after an update; the tunnel comes up on its own", s.isStartOnBootEnabled, where = Where.PHONE) { v -> patch(DexSettingsPatch(isStartOnBootEnabled = v)) })
                add(switchRow("Keep the connection in the background", "Dex is served by the phone, so this keeps this browser connected too", s.isBackgroundConnectionEnabled, where = Where.PHONE) { v -> patch(DexSettingsPatch(isBackgroundConnectionEnabled = v)) })
                add(
                    selectRow("Nebula log level", null, listOf(Choice("INFO", "Info"), Choice("DEBUG", "Debug")), s.nebulaLogLevel.name, where = Where.PHONE) { key ->
                        patch(DexSettingsPatch(nebulaLogLevel = DexLogLevel.valueOf(key)))
                    },
                )
                add(switchRow("Developer mode", null, s.isDeveloperMode, where = Where.BOTH) { v -> patch(DexSettingsPatch(isDeveloperMode = v)) })
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
                add(actionRow("Clean up", "Remove media no message refers to", button = "Clean up", isEnabled = st.orphanCount > 0, where = Where.PHONE) { actions.clearOrphans() })
                add(switchRow("Clean up automatically", "Every time the app starts", s.autoCleanOrphans, where = Where.PHONE) { v -> patch(DexSettingsPatch(autoCleanOrphans = v)) })
            },
            section("Danger", "This cannot be undone, and it happens on the phone.") {
                add(actionRow("Clear all history", "Every message in every chat", button = "Clear", isDanger = true, where = Where.BOTH) { actions.clearAllHistory() })
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
                add(actionRow("Retry everything that failed", null, button = "Retry", isEnabled = d.failedCount > 0, where = Where.PHONE) { actions.retryFailed("") })
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
                add(switchRow("Check daily", null, u.isDailyCheckEnabled, where = Where.PHONE) { v -> patch(DexSettingsPatch(isDailyUpdateCheckEnabled = v)) })
                add(actionRow("Check now", null, button = "Check", where = Where.PHONE) { actions.checkUpdates() })
                add(infoRow("Releases page, download and install", "On the phone", where = Where.PHONE))
            },
        )
    }

    private fun dex(s: DexSettings) {
        content.add(
            section("This browser's way in", "Dex is set up on the phone. A browser Dex is serving does not get to change the lock on its own door.") {
                add(infoRow("Username", s.dexUsername, where = Where.PHONE))
                add(infoRow("Client limit", s.dexMaxClients.toString(), where = Where.PHONE))
                add(infoRow("Port", if (s.dexPort == 0) "" else s.dexPort.toString(), isMono = true))
            },
        )
    }

    private companion object {
        /** the phone's own Status screen offers exactly these */
        val PAUSES = listOf(Choice("0", "Off"), Choice("30", "30 minutes"), Choice("60", "1 hour"), Choice("480", "8 hours"), Choice("1440", "24 hours"))

        val THEME_MODES = listOf(Choice("SYSTEM", "Follow the system"), Choice("LIGHT", "Light"), Choice("DARK", "Dark"))
        val TEXT_SIZES = listOf(Choice("SMALL", "Small"), Choice("MEDIUM", "Medium"), Choice("LARGE", "Large"))
        val DENSITIES = listOf(Choice("COMFORTABLE", "Comfortable"), Choice("COMPACT", "Compact"))

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

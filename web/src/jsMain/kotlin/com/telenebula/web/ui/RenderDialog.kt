package com.telenebula.web.ui

import com.telenebula.web.state.Actions
import com.telenebula.web.state.AppState
import com.telenebula.web.state.Dialog
import com.telenebula.web.state.EmojiTarget
import com.telenebula.web.wire.DexContactFlags
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.js.Date

/** The one modal: a title, the form the request carries, and a row of actions. */
class DialogView(root: HTMLElement, private val actions: Actions) {
    private val host = div("modal-scrim hidden").also { root.appendChild(it) }
    private val card = div("modal").also { it.setAttribute("role", "dialog"); it.setAttribute("aria-modal", "true") }
    private var shown: Dialog? = null
    private var shownEmojiCount = -1
    private var emojiCount = -1

    init {
        host.add(card)
        host.on("click") { e -> if (e.target === host) actions.openDialog(null) }
    }

    fun render(prev: AppState, next: AppState) {
        emojiCount = next.emoji.size
        val d = next.dialog
        host.toggle("hidden", d == null)
        if (d == null) {
            shown = null
            card.clear()
            return
        }
        // rebuilding while a field has focus would take the caret away mid-word
        if (shown != null && sameShape(shown, d)) return
        shown = d
        card.clear()
        when (d) {
            is Dialog.AddContact -> addContact(d)
            is Dialog.EditContact -> editContact(d)
            is Dialog.ChangeIp -> changeIp(d)
            is Dialog.EmojiPick -> emojiPick(d, next)
            is Dialog.MuteFor -> muteFor(d)
            is Dialog.Disappearing -> disappearing(d)
        }
    }

    private fun sameShape(a: Dialog?, b: Dialog): Boolean = when {
        a is Dialog.AddContact && b is Dialog.AddContact -> a.error == b.error
        a is Dialog.EditContact && b is Dialog.EditContact -> a.peer == b.peer
        a is Dialog.ChangeIp && b is Dialog.ChangeIp -> a.peer == b.peer && a.error == b.error
        a is Dialog.EmojiPick && b is Dialog.EmojiPick -> a.target == b.target && emojiCount == shownEmojiCount
        a is Dialog.MuteFor && b is Dialog.MuteFor -> a.peer == b.peer
        a is Dialog.Disappearing && b is Dialog.Disappearing -> a.peer == b.peer
        else -> false
    }

    private fun shell(title: String, body: HTMLElement, confirm: String?, onConfirm: (() -> Unit)?, error: String? = null) {
        card.add(div("modal-title", title))
        if (error != null) card.add(div("modal-error", error))
        card.add(body)
        val row = div("modal-actions").add(button("btn", "Cancel", { actions.openDialog(null) }, text = "Cancel"))
        if (confirm != null && onConfirm != null) row.add(button("btn btn-primary", confirm, { onConfirm() }, text = confirm))
        card.add(row)
    }

    private fun addContact(d: Dialog.AddContact) {
        val (ipField, ip) = textField("Overlay address", d.ip, "10.x.x.x")
        val (nameField, name) = textField("Name", d.name, "What they are called")
        val (nickField, nick) = textField("Nickname", d.nickname, "Optional")
        val notes = textArea("Notes", d.notes, "Optional")
        val body = div("modal-body").add(ipField, nameField, nickField, field("Notes", notes))
        shell("Add a contact", body, "Add", {
            actions.updateDialog(Dialog.AddContact(ip.value.trim(), name.value.trim(), nick.value.trim(), notes.value.trim()))
            actions.submitDialog()
        }, d.error)
        window.setTimeout({ ip.focus() }, 0)
    }

    private fun editContact(d: Dialog.EditContact) {
        val (nameField, name) = textField("Name", d.name, "What they are called")
        val (nickField, nick) = textField("Nickname", d.nickname, "Optional")
        val notes = textArea("Notes", d.notes, "Optional")
        val body = div("modal-body").add(nameField, nickField, field("Notes", notes))
        shell("Edit contact", body, "Save", {
            actions.saveContact(d.peer, name.value.trim(), nick.value.trim(), notes.value.trim())
            actions.openDialog(null)
        })
        window.setTimeout({ name.focus() }, 0)
    }

    private fun changeIp(d: Dialog.ChangeIp) {
        val (ipField, ip) = textField("New overlay address", d.newIp, "10.x.x.x")
        val body = div("modal-body").add(ipField, div("row-sub", "The conversation and its history move with them."))
        shell("Change overlay address", body, "Change", {
            actions.changeContactIp(d.peer, ip.value.trim())
        }, d.error)
        window.setTimeout({ ip.focus() }, 0)
    }

    /** The phone's whole catalogue, for a quick-reaction slot or for reacting to one message. */
    private fun emojiPick(d: Dialog.EmojiPick, state: AppState) {
        shownEmojiCount = state.emoji.size
        val title = when (d.target) {
            is EmojiTarget.Slot -> "Quick reaction ${d.target.slot + 1}"
            is EmojiTarget.React -> "React"
        }
        val body = div("modal-body").add(
            emojiPicker(state.emoji) { e ->
                when (d.target) {
                    is EmojiTarget.Slot -> actions.setQuickReaction(d.target.slot, e)
                    is EmojiTarget.React -> actions.reactById(d.target.messageId, e)
                }
                actions.openDialog(null)
            },
        )
        shell(title, body, null, null)
    }

    private fun muteFor(d: Dialog.MuteFor) {
        val body = div("modal-body")
        val options = listOf(
            "Unmute" to 0L,
            "8 hours" to 8 * 3_600_000L,
            "1 week" to 7 * 86_400_000L,
            "Always" to -1L,
        )
        for ((label, span) in options) {
            val b = button("btn btn-wide", label, {
                val until = when {
                    span == 0L -> 0L
                    span < 0L -> -1L
                    else -> Date.now().toLong() + span
                }
                actions.setContactFlags(d.peer, DexContactFlags(muteUntil = until))
                actions.openDialog(null)
            }, text = label)
            body.add(b)
        }
        shell("Mute notifications", body, null, null)
    }

    private fun disappearing(d: Dialog.Disappearing) {
        val body = div("modal-body")
        val options = listOf("Off" to 0, "1 day" to 86_400, "1 week" to 604_800, "4 weeks" to 2_419_200)
        for ((label, seconds) in options) {
            val b = button("btn btn-wide", label, {
                actions.setContactFlags(d.peer, DexContactFlags(disappearSeconds = seconds))
                actions.openDialog(null)
            }, text = label)
            body.add(b)
        }
        body.add(div("row-sub", "New messages in this chat disappear after they are read. Both sides are told."))
        shell("Disappearing messages", body, null, null)
    }

}

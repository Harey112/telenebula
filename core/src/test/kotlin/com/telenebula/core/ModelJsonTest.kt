package com.telenebula.core

import com.telenebula.core.model.CallOutcome
import com.telenebula.core.model.ChatSummary
import com.telenebula.core.model.ChatTextSize
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.ChatView
import com.telenebula.core.model.Contact
import com.telenebula.core.model.Envelope
import com.telenebula.core.model.EnvelopeType
import com.telenebula.core.model.MessageActionStatus
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus
import com.telenebula.core.model.NotificationPrefs
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.PrefsMigration
import com.telenebula.core.model.ThemeMode
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelJsonTest {
    private fun <T> roundTrip(serializer: KSerializer<T>, json: String): T {
        val first = CoreJson.decodeFromString(serializer, json)
        val second = CoreJson.decodeFromString(serializer, CoreJson.encodeToString(serializer, first))
        assertEquals(first, second)
        return first
    }

    @Test
    fun chatView_roundTrips_asTheCoreWritesIt() {
        val view = roundTrip(
            ChatView.serializer(),
            """
            {"contact":$CONTACT_JSON,
             "messages":[$MESSAGE_JSON],
             "actions":{"m1":[{"id":"a1","messageId":"m1","peerIp":"fd00::2","type":"send","payload":{},
                "status":"pending","attempts":1,"createdAt":1,"updatedAt":2}]},
             "replySources":{"m0":$MESSAGE_JSON}}
            """,
        )
        assertEquals("fd00::2", view.contact?.ip)
        assertEquals(MessageDirection.OUT, view.messages.single().direction)
        assertEquals(MessageStatus.SENT, view.messages.single().status)
        assertEquals(MessageKind.IMAGE, view.messages.single().kind)
        assertEquals("👍", view.messages.single().reactions["fd00::2"])
        assertEquals(1, view.actions.getValue("m1").size)
        assertEquals(ChatView(), CoreJson.decodeFromString(ChatView.serializer(), "{}"))
    }

    @Test
    fun chatSummary_roundTrips_withNullsForAnEmptyChat() {
        val summary = roundTrip(
            ChatSummary.serializer(),
            """{"ip":"fd00::2","name":"Ada","lastBody":null,"lastTs":null,"lastDirection":null,"unread":0,
                "pinnedAt":null,"isArchived":false,"isBlocked":false,"muteUntil":-1,"isMarkedUnread":false}""",
        )
        assertNull(summary.lastDirection)
        assertNull(summary.lastSendStatus)
        assertEquals(Prefs.MUTE_FOREVER, summary.muteUntil)
    }

    /**
     * Every status the core can write has to decode. A value the enum does not know throws, and
     * [CoreClient.chatView] answers a failed decode with an empty view, so one unknown string
     * blanks the whole conversation and drops the header back to a raw address. That is exactly
     * what an unmirrored "waiting" did when a large attachment was offered.
     */
    @Test
    fun chatView_decodesEveryActionStatusTheCoreCanWrite() {
        for (status in listOf("pending", "waiting", "success", "failed", "cancelled")) {
            val view = CoreJson.decodeFromString(
                ChatView.serializer(),
                """
                {"contact":$CONTACT_JSON,
                 "messages":[$MESSAGE_JSON],
                 "actions":{"m1":[{"id":"m1","messageId":"m1","peerIp":"fd00::2","type":"send","payload":{},
                    "status":"$status","attempts":1,"createdAt":1,"updatedAt":2}]}}
                """.trimIndent(),
            )
            assertEquals("$status kept the view", 1, view.messages.size)
            assertEquals(status, view.actions.getValue("m1").single().status.name.lowercase())
        }
    }

    /** Likewise for the message states a transfer moves through. */
    @Test
    fun chatMessage_decodesEveryStatusTheCoreCanWrite() {
        for (status in listOf("pending", "delivered", "offered", "declined", "receiving", "received")) {
            val decoded = CoreJson.decodeFromString(
                ChatMessage.serializer(),
                """{"id":"m9","peerIp":"fd00::2","direction":"in","body":"","ts":1,"status":"$status","kind":"file"}""",
            )
            assertEquals(status, decoded.status.name.lowercase())
        }
    }

    /** A core too old to report it leaves the field absent, which must read as "nothing to show". */
    @Test
    fun chatSummary_decodesTheLastSendStatus() {
        val failed = roundTrip(
            ChatSummary.serializer(),
            """{"ip":"fd00::2","name":"Ada","lastBody":"hi","lastTs":7,"lastDirection":"out",
                "lastStatus":"pending","lastSendStatus":"failed","unread":0}""",
        )
        assertEquals(MessageStatus.PENDING, failed.lastStatus)
        assertEquals(MessageActionStatus.FAILED, failed.lastSendStatus)

        val cancelled = CoreJson.decodeFromString(
            ChatSummary.serializer(),
            """{"ip":"fd00::2","name":"Ada","lastDirection":"out","lastSendStatus":"cancelled"}""",
        )
        assertEquals(MessageActionStatus.CANCELLED, cancelled.lastSendStatus)
    }

    @Test
    fun contact_roundTrips_withPerContactNotificationOverrides() {
        val contact = roundTrip(Contact.serializer(), CONTACT_JSON)
        assertEquals("1.2.0", contact.clientVersion)
        assertFalse(contact.notifications?.useGlobal ?: true)
        assertFalse(contact.notifications?.sound ?: true)
        assertTrue(contact.notifications?.preview ?: false)
    }

    @Test
    fun envelope_decodesUnknownKeysAndExplicitNullCandidate() {
        val envelope = roundTrip(
            Envelope.serializer(),
            """{"v":1,"type":"call-ice","id":"e1","from":{"ip":"fd00::2","name":"Ada"},"ts":5,
                "callId":"c1","candidate":null,"future":"ignored"}""",
        )
        assertEquals(EnvelopeType.CALL_ICE, envelope.type)
        assertEquals("fd00::2", envelope.from.ip)
        assertEquals("c1", envelope.callId)
        assertNull(envelope.candidate)
        assertEquals(EnvelopeType.UNKNOWN, CoreJson.decodeFromString(Envelope.serializer(), """{"type":"Unknown"}""").type)
    }

    @Test
    fun prefs_partialFile_mergesOverDefaultsAtEveryLevel() {
        // a file every shipped build wrote: flat, versionless, and only what differs from the defaults
        val prefs = PrefsMigration.decode(
            CoreJson,
            """{"themeMode":"dark","chatTextSize":"large","notifications":{"quietHours":{"enabled":true}}}""",
        )
        assertEquals(ThemeMode.DARK, prefs.app.themeMode)
        assertEquals(ChatTextSize.LARGE, prefs.app.chatTextSize)
        assertTrue(prefs.core.notifications.quietHours.enabled)
        assertEquals(22, prefs.core.notifications.quietHours.fromHour)
        assertTrue(prefs.app.notificationSound)
        assertEquals(Prefs.DEFAULT_QUICK_REACTIONS, prefs.core.quickReactions)
        assertEquals(Prefs(), CoreJson.decodeFromString(Prefs.serializer(), """{"version":2}"""))
    }

    @Test
    fun prefs_unknownEnumValue_fallsBackToTheDefault() {
        val prefs = PrefsMigration.decode(CoreJson, """{"themeMode":"sepia"}""")
        assertEquals(ThemeMode.SYSTEM, prefs.app.themeMode)
    }

    @Test
    fun notificationPrefs_roundTrip_keepsOnlyWhatChanged() {
        // sound, preview and enabled belong to a profile now; what is left here still round-trips
        val prefs = roundTrip(NotificationPrefs.serializer(), """{"messages":{"vibrate":false},"inApp":{"vibrate":true}}""")
        assertFalse(prefs.messages.vibrate)
        assertTrue(prefs.messages.popup)
        assertTrue(prefs.inApp.vibrate)
        assertEquals("""{"messages":{"vibrate":false},"inApp":{"vibrate":true}}""", CoreJson.encodeToString(NotificationPrefs.serializer(), prefs))
    }

    @Test
    fun notificationPrefs_dropsTheRetiredBadgeBlock() {
        // an install from before the tab badge was removed still has it on disk
        val prefs = CoreJson.decodeFromString(NotificationPrefs.serializer(), """{"badge":{"includeMuted":true},"inApp":{"vibrate":true}}""")
        assertTrue(prefs.inApp.vibrate)
    }

    @Test
    fun callOutcome_usesTheWireSpelling() {
        assertEquals("\"no-answer\"", CoreJson.encodeToString(CallOutcome.serializer(), CallOutcome.NO_ANSWER))
    }

    private companion object {
        const val CONTACT_JSON = """{"ip":"fd00::2","name":"Ada","nickname":"","notes":"","addedAt":1,"lastSeenAt":null,
            "pinnedAt":null,"isArchived":false,"isBlocked":false,"muteUntil":0,"isMarkedUnread":false,
            "notifications":{"useGlobal":false,"messages":true,"preview":true,"sound":false,"vibrate":true,"popup":true,"reactions":true,"calls":true},
            "clientVersion":"1.2.0","disappearSeconds":0}"""
        const val MESSAGE_JSON = """{"id":"m1","peerIp":"fd00::2","direction":"out","body":"","ts":3,"status":"sent","kind":"image",
            "attachment":{"name":"a.png","mime":"image/png","size":12,"uri":"/data/a.png"},"isEdited":false,"isDeleted":false,
            "reactions":{"fd00::2":"👍"},"replyToId":"m0","seenAt":null,"expireSecs":null,"expiresAt":null}"""
    }
}

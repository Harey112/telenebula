package com.telenebula.core

import app.cash.turbine.test
import com.telenebula.core.events.CoreEvent
import com.telenebula.core.events.CoreEventBus
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.ChatSummary
import com.telenebula.core.model.ChatView
import com.telenebula.core.model.Contact
import com.telenebula.core.model.MessageDirection
import com.telenebula.core.model.MessageKind
import com.telenebula.core.model.MessageStatus
import com.telenebula.core.model.NotificationPrefs
import com.telenebula.core.model.PeerStats
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoreClientTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"), "tn-core-test")
    private val errors = ArrayList<String>()
    private val services = object : CoreServices {
        val cleared = ArrayList<String>()
        override fun clearChatNotification(ip: String) { cleared += ip }
        override fun setNotificationPrefs(prefs: NotificationPrefs) = Unit
        override fun startBackgroundService() = Unit
        override fun stopBackgroundService() = Unit
        override fun setTunnelState(running: Boolean) = Unit
    }

    private fun client(core: MessagingCore, io: kotlinx.coroutines.CoroutineDispatcher) = CoreClient(
        core = core,
        paths = CorePaths(File(tmp, "files"), File(tmp, "cache")),
        bus = CoreEventBus,
        errorSink = { errors += it },
        io = io,
        services = services,
    )

    @Test
    fun query_returnsTheFallback_quietlyBeforeTheStoreOpens_andReportsAnyOtherFailure() = runTest {
        val core = object : FakeCore() {
            override fun chatView(peerIp: String, limit: Int): ChatView = throw CoreException.notRunning()
            override fun peerStats(peerIp: String): PeerStats = throw IllegalStateException("disk I/O error")
        }
        val client = client(core, StandardTestDispatcher(testScheduler))
        assertEquals(ChatView(), client.chatView("fd00::2"))
        assertEquals(0, client.unreadTotal())
        assertEquals("a core that is not running yet is not an error", emptyList<Any>(), errors)
        assertEquals(PeerStats(), client.peerStats("fd00::2"))
        assertEquals("a corrupt store must not render as an empty app", listOf("Messaging engine error: disk I/O error"), errors)
    }

    @Test
    fun command_reportsTheEngineError_andStillClearsTheNotification() = runTest {
        val core = object : FakeCore() {
            override fun sendText(peerIp: String, body: String, replyToId: String?, cover: String?) = throw RuntimeException("db locked")
        }
        val client = client(core, StandardTestDispatcher(testScheduler))
        client.sendText("fd00::2", "hi")
        client.markChatRead("fd00::2")
        assertEquals(listOf("Messaging engine error: db locked"), errors)
        assertEquals(listOf("fd00::2"), services.cleared)
    }

    @Test
    fun reactiveQuery_coalescesBurstsOfInvalidations() = runTest {
        var calls = 0
        val core = object : FakeCore() {
            // a different list on every read, otherwise the shared state (rightly) swallows equal results
            override fun chatSummaries(): List<ChatSummary> {
                calls++
                return listOf(ChatSummary(ip = "fd00::$calls", name = "peer $calls"))
            }
        }
        val client = client(core, StandardTestDispatcher(testScheduler))
        client.openStore()
        client.chatSummariesFlow().test {
            assertEquals("fd00::1", awaitItem().single().ip)
            assertEquals(1, calls)
            CoreEventBus.emit(CoreEvent.SummariesChanged)
            CoreEventBus.emit(CoreEvent.ChatChanged("fd00::2"))
            CoreEventBus.emit(CoreEvent.SummariesChanged)
            assertEquals("fd00::2", awaitItem().single().ip)
            assertEquals(2, calls)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sharedState_keepsTheLastResult_afterTheLastSubscriberLeaves() = runTest {
        var calls = 0
        val core = object : FakeCore() {
            override fun contacts(): List<Contact> {
                calls++
                return listOf(Contact(ip = "fd00::2", name = "peer", addedAt = 1))
            }
        }
        val client = client(core, StandardTestDispatcher(testScheduler))
        assertEquals(null, client.contacts.value)
        assertEquals(null, client.cachedContact("fd00::2"))
        client.openStore()
        client.contactsFlow().test {
            assertEquals("peer", awaitItem().single().name)
            cancelAndIgnoreRemainingEvents()
        }
        // the seed for the next screen's first frame, with no subscriber alive
        assertEquals("peer", client.cachedContact("fd00::2")?.name)
        assertEquals(1, calls)
    }

    @Test
    fun chatFlow_patchesNamedRows_andReadsTheChatAgainOnlyForStructuralChanges() = runTest {
        var fullReads = 0
        var rowReads = 0
        fun message(id: String, body: String, ts: Long) = ChatMessage(id = id, peerIp = "fd00::2", direction = MessageDirection.IN, body = body, ts = ts, status = MessageStatus.RECEIVED, kind = MessageKind.TEXT)
        val core = object : FakeCore() {
            override fun chatView(peerIp: String, limit: Int): ChatView {
                fullReads++
                return ChatView(messages = listOf(message("m1", "hi", 1), message("m2", "there", 2)))
            }
            override fun chatRows(peerIp: String, ids: Collection<String>): ChatView {
                rowReads++
                assertEquals(setOf("m2"), ids.toSet())
                return ChatView(messages = listOf(message("m2", "there (edited)", 2)))
            }
        }
        val client = client(core, StandardTestDispatcher(testScheduler))
        client.openStore()
        client.chatViewFlow("fd00::2").test {
            assertEquals(listOf("hi", "there"), awaitItem().messages.map { it.body })
            CoreEventBus.emit(CoreEvent.ChatChanged("fd00::2", setOf("m2")))
            assertEquals(listOf("hi", "there (edited)"), awaitItem().messages.map { it.body })
            assertEquals(1 to 1, fullReads to rowReads)
            CoreEventBus.emit(CoreEvent.ChatChanged("fd00::2"))
            assertEquals(listOf("hi", "there"), awaitItem().messages.map { it.body })
            assertEquals(2 to 1, fullReads to rowReads)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun chatViewState_isSharedPerPeer_andServesTheCachedViewAtOnce() = runTest {
        var calls = 0
        val core = object : FakeCore() {
            override fun chatView(peerIp: String, limit: Int): ChatView {
                calls++
                return ChatView(
                    messages = listOf(
                        ChatMessage(
                            id = "m$calls",
                            peerIp = peerIp,
                            direction = MessageDirection.IN,
                            body = "hi",
                            ts = calls.toLong(),
                            status = MessageStatus.RECEIVED,
                            kind = MessageKind.TEXT,
                        ),
                    ),
                )
            }
        }
        val client = client(core, StandardTestDispatcher(testScheduler))
        client.openStore()
        val state = client.chatViewState("fd00::2")
        assertEquals(state, client.chatViewState("fd00::2"))
        client.chatViewFlow("fd00::2").test {
            assertEquals("m1", awaitItem().messages.single().id)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("m1", state.value?.messages?.single()?.id)
    }
}

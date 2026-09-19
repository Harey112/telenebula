package com.telenebula.core

import com.telenebula.core.db.JdbcSqlDb
import com.telenebula.core.db.Schema
import com.telenebula.core.db.Store
import com.telenebula.core.events.CoreEvent
import com.telenebula.core.model.CoreStartConfig
import java.io.File
import java.net.ServerSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TnCoreTest {
    private val scratch = File(System.getProperty("java.io.tmpdir"), "tn-core-${System.nanoTime()}").apply { mkdirs() }
    private val events = ArrayList<CoreEvent>()
    private val core = TnCore(CorePaths(File(scratch, "files"), File(scratch, "cache")), events::add) { path ->
        File(path).parentFile?.mkdirs()
        val db = JdbcSqlDb.open(path)
        Schema.apply(db)
        Store(db, path, JdbcSqlDb::open)
    }

    @After
    fun tearDown() {
        core.stop()
        scratch.deleteRecursively()
    }

    @Test
    fun `a query before the store is open says so instead of guessing`() {
        try {
            core.contacts()
            fail("expected not running")
        } catch (e: CoreException) {
            assertEquals(CoreException.Kind.NOT_RUNNING, e.kind)
        }
    }

    @Test
    fun `start and stop are idempotent and the store stays readable after a stop`() {
        val port = ServerSocket(0).use { it.localPort }
        val config = CoreStartConfig(overlayIp = "127.0.0.1", displayName = "me", msgPort = port, sendReadReceipts = true, appVersion = "t")
        core.start(config)
        core.start(config)
        core.upsertContact("fd::9", "nine")
        core.stop()
        core.stop()
        assertEquals("nine", core.contact("fd::9")?.name)
        try {
            core.sendText("fd::9", "hi", null, null)
            fail("a command needs the engine")
        } catch (e: CoreException) {
            assertEquals(CoreException.Kind.NOT_RUNNING, e.kind)
        }
    }

    @Test
    fun `an empty new address is refused`() {
        core.openStore()
        core.upsertContact("fd::1", "one")
        try {
            core.changeContactIp("fd::1", "")
            fail("expected a refusal")
        } catch (e: CoreException) {
            assertTrue(e.message.orEmpty().contains("empty"))
        }
    }
}

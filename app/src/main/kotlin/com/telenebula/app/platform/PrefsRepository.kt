package com.telenebula.app.platform

import android.content.Context
import com.telenebula.core.model.NotificationPrefs
import com.telenebula.core.model.Prefs
import com.telenebula.core.model.PrefsMigration
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * App preferences in `files/prefs.json`, identical keys to the RN build (the backup reads
 * that file). Missing keys take the data-class defaults, which is the old deep merge for free.
 * Writes are conflated: a burst of toggles produces one file write of the latest value.
 */
class PrefsRepository(
    context: Context,
    private val json: Json,
    scope: CoroutineScope,
    /** mirrors the notification section to the native cache used while the UI is not running */
    private val mirrorNotifications: (NotificationPrefs) -> Unit,
    /** tells the messaging core, which is what actually stops the receipts going out */
    private val mirrorReadReceipts: (Boolean, Boolean) -> Unit = { _, _ -> },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val file = File(context.filesDir, FILE_NAME)
    private val mutable = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = mutable.asStateFlow()

    private val writes = MutableSharedFlow<Prefs>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Why the last [load] fell back to the defaults, or null when it read the file. */
    @Volatile
    var loadFailure: String? = null
        private set

    init {
        scope.launch(io) {
            writes.collectLatest { FileIo.writeAtomic(file, json.encodeToString(Prefs.serializer(), it)) }
        }
    }

    suspend fun load(): Prefs = withContext(io) {
        val loaded = when (val read = FileIo.read(file)) {
            FileRead.Missing -> Prefs()
            is FileRead.Failed -> Prefs().also { loadFailure = read.cause.userMessage() }
            // through the migration, never the serializer: a file older than the split reads as flat
            is FileRead.Text -> runCatching { PrefsMigration.decode(json, read.value) }
                .getOrElse { Prefs().also { _ -> loadFailure = it.userMessage() } }
        }
        mutable.value = loaded
        mirrorNotifications(loaded.core.notifications)
        mirrorReadReceipts(loaded.app.sendReadReceipts, loaded.app.sendReadReceipts || loaded.dex.sendReadReceipts)
        loaded
    }

    fun update(transform: (Prefs) -> Prefs) {
        var previous = mutable.value
        val next = mutable.updateAndGet { current ->
            previous = current
            transform(current)
        }
        if (next === previous) return
        if (next.core.notifications != previous.core.notifications) mirrorNotifications(next.core.notifications)
        if (next.app.sendReadReceipts != previous.app.sendReadReceipts || next.dex.sendReadReceipts != previous.dex.sendReadReceipts) {
            mirrorReadReceipts(next.app.sendReadReceipts, next.app.sendReadReceipts || next.dex.sendReadReceipts)
        }
        writes.tryEmit(next)
    }

    /** Moves the emoji to the front of the recent list and trims it; quick reactions are never recent. */
    fun recordRecentReaction(emoji: String) = update { p ->
        if (emoji in p.core.quickReactions) return@update p
        val next = ArrayList<String>(Reactions.MAX_RECENT)
        next.add(emoji)
        for (e in p.core.recentReactions) if (e != emoji && next.size < Reactions.MAX_RECENT) next.add(e)
        p.copy(core = p.core.copy(recentReactions = next))
    }

    /** Puts the emoji in one quick-reaction slot; if it already sits elsewhere the two slots swap. */
    fun setQuickReaction(slot: Int, emoji: String) = update { p ->
        val next = p.core.quickReactions.toMutableList()
        val previous = next.getOrNull(slot) ?: return@update p
        val existing = next.indexOf(emoji)
        if (existing != -1) next[existing] = previous
        next[slot] = emoji
        p.copy(core = p.core.copy(quickReactions = next, recentReactions = p.core.recentReactions.filterNot { it in next }))
    }

    private companion object {
        const val FILE_NAME = "prefs.json"
    }
}

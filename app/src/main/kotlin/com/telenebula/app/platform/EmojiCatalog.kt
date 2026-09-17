package com.telenebula.app.platform

import android.content.Context
import com.telenebula.core.CoreJson
import com.telenebula.core.model.EmojiGroup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/** The ~1,900-emoji catalog, loaded once from assets on first use (a Kotlin literal table would bloat dex). */
class EmojiCatalog(
    context: Context,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val app = context.applicationContext

    /** Read on the first collection and kept for the process; empty until it arrives. */
    val groups: StateFlow<List<EmojiGroup>> =
        flow { emit(load()) }.stateIn(scope, SharingStarted.Lazily, emptyList())

    private suspend fun load(): List<EmojiGroup> = withContext(io) {
        val raw = app.assets.open(ASSET).bufferedReader().use { it.readText() }
        CoreJson.decodeFromString(ListSerializer(EmojiGroup.serializer()), raw)
    }

    private companion object {
        const val ASSET = "emoji_catalog.json"
    }
}

/** Default for the quickReactions preference and the recent-list cap; the RN `constants/emojis.ts`. */
object Reactions {
    val QUICK: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
    const val MAX_RECENT = 21
}

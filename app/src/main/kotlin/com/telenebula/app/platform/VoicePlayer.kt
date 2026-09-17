package com.telenebula.app.platform

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VoicePlayback(val messageId: String, val positionMs: Long, val durationMs: Long, val isPlaying: Boolean)

/** One player for the whole app: tapping a second clip stops the first. */
class VoicePlayer(private val scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val mutable = MutableStateFlow<VoicePlayback?>(null)
    val state: StateFlow<VoicePlayback?> = mutable.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /** Plays, pauses or resumes [messageId]; throws when the file cannot be decoded. */
    suspend fun toggle(messageId: String, file: File, knownDurationMs: Long?) = withContext(io) {
        val current = mutable.value
        val active = player
        if (active != null && current?.messageId == messageId) {
            if (active.isPlaying) {
                active.pause()
                ticker?.cancel()
                mutable.value = current.copy(isPlaying = false, positionMs = active.currentPosition.toLong())
            } else {
                active.start()
                mutable.value = current.copy(isPlaying = true)
                startTicker(messageId)
            }
            return@withContext
        }
        release()
        val fresh = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            setDataSource(file.path)
            prepare()
            setOnCompletionListener {
                ticker?.cancel()
                mutable.update { it?.takeIf { p -> p.messageId == messageId }?.copy(isPlaying = false, positionMs = 0) ?: it }
            }
        }
        player = fresh
        val duration = fresh.duration.toLong().takeIf { it > 0 } ?: knownDurationMs ?: 0L
        mutable.value = VoicePlayback(messageId, 0, duration, true)
        fresh.start()
        startTicker(messageId)
    }

    fun release() {
        ticker?.cancel()
        player?.release()
        player = null
        mutable.value = null
    }

    /** runs only while a clip plays; the position is read off the player rather than counted */
    private fun startTicker(messageId: String) {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                delay(TICK_MS)
                val active = player ?: break
                mutable.update { it?.takeIf { p -> p.messageId == messageId && p.isPlaying }?.copy(positionMs = active.currentPosition.toLong()) ?: it }
            }
        }
    }

    private companion object {
        const val TICK_MS = 250L
    }
}

package com.telenebula.app.platform

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException

/** One recording at a time into a file the caller names; AAC in MP4 plays on every supported Android. */
class VoiceRecorder(context: Context) {
    private val app = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAtMs = 0L

    fun start(target: File) {
        cancel()
        val fresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(app) else @Suppress("DEPRECATION") MediaRecorder()
        fresh.setAudioSource(MediaRecorder.AudioSource.MIC)
        fresh.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        fresh.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        fresh.setAudioEncodingBitRate(BIT_RATE)
        fresh.setAudioSamplingRate(SAMPLE_RATE)
        fresh.setOutputFile(target.path)
        fresh.prepare()
        fresh.start()
        recorder = fresh
        file = target
        startedAtMs = System.currentTimeMillis()
    }

    /** Finishes the file and returns its length in ms; throws when nothing usable was captured. */
    fun stop(): Long {
        val active = recorder ?: return 0L
        val length = System.currentTimeMillis() - startedAtMs
        try {
            active.stop()
        } catch (e: RuntimeException) {
            file?.delete()
            throw IOException("Nothing was recorded")
        } finally {
            active.release()
            recorder = null
            file = null
        }
        return length
    }

    fun cancel() {
        recorder?.let { active ->
            runCatching { active.stop() }
            active.release()
        }
        recorder = null
        file?.delete()
        file = null
    }

    private companion object {
        const val BIT_RATE = 64_000
        const val SAMPLE_RATE = 44_100
    }
}

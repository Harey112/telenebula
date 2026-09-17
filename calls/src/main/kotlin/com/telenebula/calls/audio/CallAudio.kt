package com.telenebula.calls.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import androidx.annotation.RequiresApi
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.telenebula.calls.CallAudioPort

/**
 * The call's audio session: focus, communication mode, one routing authority, and the two
 * progress tones. The speaker flag is re-applied on every device change so neither a headset
 * plugging in nor libwebrtc's audio module can silently flip the route. Main thread only.
 */
class CallAudio(context: Context) : CallAudioPort {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private var isActive = false
    private var speakerOn = false
    private var ringback: MediaPlayer? = null
    private var busy: MediaPlayer? = null
    private var proximityLock: PowerManager.WakeLock? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = reapplyRoute()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = reapplyRoute()
    }

    /** Takes focus, enters communication mode and routes. Call after media exists, before ringing. */
    override fun start(speaker: Boolean) {
        if (!isActive) {
            isActive = true
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(VOICE)
                .setAcceptsDelayedFocusGain(false)
                .build()
                .also { audioManager.requestAudioFocus(it) }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        }
        setSpeaker(speaker)
    }

    override fun setSpeaker(on: Boolean) {
        speakerOn = on
        if (isActive) reapplyRoute()
    }

    override fun startRingback() {
        if (ringback != null) return
        ringback = play(RINGBACK_ASSET, isLooping = true)
    }

    override fun stopRingback() {
        ringback?.let { runCatching { it.stop() }; it.release() }
        ringback = null
    }

    /** Ends the session; with [playBusyTone] the three beeps tell the caller the attempt failed. */
    override fun stop(playBusyTone: Boolean) {
        stopRingback()
        setProximityEnabled(false)
        if (isActive) {
            isActive = false
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice() else legacyRoute(false)
            audioManager.mode = AudioManager.MODE_NORMAL
            focus?.let { audioManager.abandonAudioFocusRequest(it) }
            focus = null
        }
        if (playBusyTone) {
            busy?.release()
            busy = play(BUSY_ASSET, isLooping = false)?.also { player ->
                player.setOnCompletionListener {
                    it.release()
                    if (busy === it) busy = null
                }
            }
        }
    }

    /** Screen off against the ear while on an earpiece call; released for speaker and headsets. */
    override fun setProximityEnabled(enabled: Boolean) {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (enabled && !speakerOn && !hasWiredOrBluetooth()) {
            if (proximityLock?.isHeld == true) return
            if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
            proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, PROXIMITY_TAG).also { it.acquire() }
        } else {
            proximityLock?.let { if (it.isHeld) it.release() }
            proximityLock = null
        }
    }

    private fun reapplyRoute() {
        if (!isActive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) modernRoute() else legacyRoute(speakerOn)
        setProximityEnabled(proximityLock != null)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun modernRoute() {
        val devices = audioManager.availableCommunicationDevices
        val target = if (speakerOn) {
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } else {
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: devices.firstOrNull { it.type in WIRED_TYPES }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }
        if (target != null) audioManager.setCommunicationDevice(target) else audioManager.clearCommunicationDevice()
    }

    @Suppress("DEPRECATION")
    private fun legacyRoute(speaker: Boolean) {
        val hasBluetooth = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (!speaker && hasBluetooth) {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        } else {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        }
        audioManager.isSpeakerphoneOn = speaker
    }

    private fun hasWiredOrBluetooth(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in WIRED_TYPES || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }

    private fun play(asset: String, isLooping: Boolean): MediaPlayer? = runCatching {
        MediaPlayer().apply {
            setAudioAttributes(VOICE)
            app.assets.openFd(asset).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            this.isLooping = isLooping
            prepare()
            start()
        }
    }.getOrNull()

    private companion object {
        val VOICE: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val WIRED_TYPES = intArrayOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)
        const val RINGBACK_ASSET = "incallmanager_ringback.wav"
        const val BUSY_ASSET = "incallmanager_busytone.wav"
        const val PROXIMITY_TAG = "telenebula:call-proximity"
    }
}

package com.telenebula.app.platform

import android.Manifest

/** The permission a call could not get, so the warning can name it. */
enum class CallPermission(private val label: String) {
    MICROPHONE("Microphone"),
    CAMERA("Camera"),
    ;

    fun needed(toDo: String): String = "$label access is needed to $toDo."
}

/**
 * WebRTC's audio device module fails to capture the microphone in total silence when
 * RECORD_AUDIO is missing — nothing throws, so the permission must be secured up front rather
 * than discovered from a failed call. Camera is requested the same way for a video call.
 * Returns the permission that was refused, or null when everything needed is granted.
 */
suspend fun ActivityGateway.deniedCallPermission(video: Boolean): CallPermission? {
    if (!requestPermission(Manifest.permission.RECORD_AUDIO)) return CallPermission.MICROPHONE
    if (video && !requestPermission(Manifest.permission.CAMERA)) return CallPermission.CAMERA
    return null
}

/** Only the camera, for turning it on during an audio call. */
suspend fun ActivityGateway.deniedCameraPermission(): CallPermission? =
    if (requestPermission(Manifest.permission.CAMERA)) null else CallPermission.CAMERA

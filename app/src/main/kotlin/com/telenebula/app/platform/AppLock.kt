package com.telenebula.app.platform

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Locks the app on launch and after it spends `lockAfterSec` in the background. Unlocking is the
 * system prompt (biometrics with the device PIN as fallback). The UI shows an opaque gate while
 * [isLocked] and asks for the prompt whenever it is resumed and still locked.
 */
class AppLock(
    private val context: Context,
    private val isEnabled: () -> Boolean,
    private val lockAfterSec: () -> Int,
) : DefaultLifecycleObserver {
    private val mutable = MutableStateFlow(false)
    /** true only while the lock is enabled and engaged */
    val isLocked: StateFlow<Boolean> = mutable.asStateFlow()

    private var backgroundedAt = 0L
    private var isPromptOpen = false

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** Once the preferences are loaded, never before: a launch with the lock enabled starts locked. */
    fun arm() {
        if (isEnabled()) mutable.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!isEnabled()) return
        if (backgroundedAt > 0) {
            if (System.currentTimeMillis() - backgroundedAt >= lockAfterSec() * 1000L) mutable.value = true
            backgroundedAt = 0
        }
    }

    /** Device credential or biometrics available and enrolled. */
    fun canUseDeviceAuth(): Boolean {
        val biometric = BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
        if (biometric == BiometricManager.BIOMETRIC_SUCCESS) return true
        return (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure == true
    }

    /** Opens the system prompt once; a success unlocks. */
    fun prompt(activity: FragmentActivity) {
        if (isPromptOpen || !mutable.value) return
        isPromptOpen = true
        show(activity, PROMPT_TITLE) {
            backgroundedAt = 0
            mutable.value = false
        }
    }

    /** The same system prompt for something other than the app gate; the caller decides what a success means. */
    fun authenticate(activity: FragmentActivity, title: String, onSuccess: () -> Unit) {
        if (isPromptOpen) return
        isPromptOpen = true
        show(activity, title, onSuccess)
    }

    private fun show(activity: FragmentActivity, title: String, onSuccess: () -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isPromptOpen = false
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    isPromptOpen = false
                }
            },
        ).authenticate(info)
    }

    private companion object {
        const val PROMPT_TITLE = "Unlock TeleNebula"
    }
}

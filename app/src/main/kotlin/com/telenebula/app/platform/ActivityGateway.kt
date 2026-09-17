package com.telenebula.app.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred

/**
 * Every system round-trip that needs an Activity (VPN consent, runtime permissions, pickers)
 * goes through here. Callers suspend; the result arrives from the launcher callback. Only one
 * request per launcher can be in flight, which matches how the UI uses them.
 */
class ActivityGateway {
    private var activity: ComponentActivity? = null
    private var consent: ActivityResultLauncher<Intent>? = null
    private var permission: ActivityResultLauncher<String>? = null
    private var openDocument: ActivityResultLauncher<Array<String>>? = null
    private var pickMedia: ActivityResultLauncher<PickVisualMediaRequest>? = null

    private var pendingConsent: CompletableDeferred<Boolean>? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var pendingDocument: CompletableDeferred<Uri?>? = null
    private var pendingMedia: CompletableDeferred<Uri?>? = null

    /** Starts an activity from the attached one; a caller that kept its own Activity reference would leak it. */
    fun startActivity(build: (Context) -> Intent) {
        val host = activity ?: return
        host.startActivity(build(host))
    }

    /** Registers the launchers; must run before the activity is STARTED (call from onCreate). */
    fun attach(host: ComponentActivity) {
        // a recreated Activity attaches before the old one detaches; a request parked on the old launchers would wait forever
        if (activity != null && activity !== host) cancelPending()
        activity = host
        consent = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            pendingConsent?.complete(it.resultCode == Activity.RESULT_OK)
            pendingConsent = null
        }
        permission = host.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingPermission?.complete(it)
            pendingPermission = null
        }
        openDocument = host.registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            pendingDocument?.complete(it)
            pendingDocument = null
        }
        pickMedia = host.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) {
            pendingMedia?.complete(it)
            pendingMedia = null
        }
    }

    fun detach(host: ComponentActivity) {
        if (activity !== host) return
        activity = null
        consent = null
        permission = null
        openDocument = null
        pickMedia = null
        cancelPending()
    }

    private fun cancelPending() {
        pendingConsent?.complete(false)
        pendingPermission?.complete(false)
        pendingDocument?.complete(null)
        pendingMedia?.complete(null)
        pendingConsent = null
        pendingPermission = null
        pendingDocument = null
        pendingMedia = null
    }

    val hasActivity: Boolean get() = activity != null

    /** Launches a consent screen (e.g. VpnService.prepare); false when denied or no activity. */
    suspend fun requestConsent(intent: Intent): Boolean {
        val launcher = consent ?: return false
        return await(pendingConsent, { pendingConsent = it }) { launcher.launch(intent) }
    }

    fun hasPermission(permissionName: String): Boolean {
        val host = activity ?: return false
        return ContextCompat.checkSelfPermission(host, permissionName) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun requestPermission(permissionName: String): Boolean {
        if (hasPermission(permissionName)) return true
        val launcher = permission ?: return false
        return await(pendingPermission, { pendingPermission = it }) { launcher.launch(permissionName) }
    }

    /** POST_NOTIFICATIONS exists from API 33; earlier versions are implicitly granted. */
    suspend fun requestPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestPermission(android.Manifest.permission.POST_NOTIFICATIONS) else true

    fun hasPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPermission(android.Manifest.permission.POST_NOTIFICATIONS)

    /** System document picker; null when cancelled. */
    suspend fun pickDocument(mimeTypes: Array<String> = arrayOf("*/*")): Uri? {
        val launcher = openDocument ?: return null
        return await(pendingDocument, { pendingDocument = it }) { launcher.launch(mimeTypes) }
    }

    /** Photo picker for images and videos; null when cancelled. */
    suspend fun pickVisualMedia(): Uri? {
        val launcher = pickMedia ?: return null
        return await(pendingMedia, { pendingMedia = it }) {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
    }

    private suspend fun <T> await(
        inFlight: CompletableDeferred<T>?,
        store: (CompletableDeferred<T>?) -> Unit,
        launch: () -> Unit,
    ): T {
        inFlight?.let { return it.await() }
        val deferred = CompletableDeferred<T>()
        store(deferred)
        launch()
        return deferred.await()
    }
}

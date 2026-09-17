package com.telenebula.app.runtime

import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.platform.Release
import com.telenebula.app.platform.UpdateChecker
import com.telenebula.app.platform.UpdateNotifier
import com.telenebula.app.platform.compareVersions
import com.telenebula.app.platform.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The daily release check. The timer waits out the day since the last answered check, so a
 * process that restarts often does not re-check on every start; a failed check is retried after
 * an hour without moving that mark. Each newer release is notified once.
 */
class UpdateMonitor(
    private val prefs: PrefsRepository,
    private val checker: UpdateChecker,
    private val notifier: UpdateNotifier,
    private val scope: CoroutineScope,
    private val appVersion: String,
    private val abis: List<String>,
) {
    private val mutableLastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = mutableLastError.asStateFlow()

    private val mutableRelease = MutableStateFlow<Release?>(null)
    /** the last answer in this process; null after a restart until the next check */
    val latestRelease: StateFlow<Release?> = mutableRelease.asStateFlow()

    val isUpdateAvailable: StateFlow<Boolean> = prefs.prefs.map { isNewer(it.updates.latestVersion) }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, isNewer(prefs.prefs.value.updates.latestVersion))

    fun isNewer(version: String?): Boolean = version != null && compareVersions(version, appVersion) > 0

    /** Called once the prefs are loaded, or the first check would write over them. */
    fun start() {
        scope.launch {
            prefs.prefs.map { it.updates.isDailyCheckEnabled }.distinctUntilChanged().collectLatest { enabled ->
                if (!enabled) return@collectLatest
                while (true) {
                    val wait = prefs.prefs.value.updates.lastCheckedAt + DAY_MS - System.currentTimeMillis()
                    if (wait > 0) delay(wait.coerceAtMost(DAY_MS))
                    if (check() == null) delay(RETRY_MS)
                }
            }
        }
    }

    /** One check; records the answer, announces a newer release once. Null when it failed, with [lastError] set. */
    suspend fun check(): Release? {
        return try {
            val release = checker.latestRelease(abis)
            mutableRelease.value = release
            mutableLastError.value = null
            prefs.update { it.copy(updates = it.updates.copy(lastCheckedAt = System.currentTimeMillis(), latestVersion = release.version)) }
            if (isNewer(release.version) && prefs.prefs.value.updates.notifiedVersion != release.version) {
                notifier.show(release.version)
                prefs.update { it.copy(updates = it.updates.copy(notifiedVersion = release.version)) }
            }
            release
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableLastError.value = e.userMessage()
            null
        }
    }

    /** The user has seen the Updates screen; the notification has done its job. */
    fun acknowledge() = notifier.clear()

    private companion object {
        const val DAY_MS = 24 * 60 * 60_000L
        const val RETRY_MS = 60 * 60_000L
    }
}

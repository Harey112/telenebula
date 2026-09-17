package com.telenebula.app.ui.screens.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.BuildConfig
import com.telenebula.app.nav.Navigator
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.notices.Prompt
import com.telenebula.app.platform.DownloadStep
import com.telenebula.app.platform.Format
import com.telenebula.app.platform.Links
import com.telenebula.app.platform.OpenWith
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.app.platform.Release
import com.telenebula.app.platform.UpdateInstaller
import com.telenebula.app.platform.userMessage
import com.telenebula.app.runtime.UpdateMonitor
import com.telenebula.app.ui.shared.uiState
import com.telenebula.core.model.UpdatePrefs
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the APK is on its way from the release to the installer. */
sealed interface Download {
    data object Idle : Download
    class Running(val fraction: Float) : Download
    class Ready(val file: File) : Download
}

data class UpdatesUiState(
    val appVersion: String,
    val buildNumber: String,
    val latestVersion: String? = null,
    val isUpdateAvailable: Boolean = false,
    val isDailyCheckEnabled: Boolean = true,
    val lastCheckedText: String = "Never",
    val lastError: String? = null,
    val download: Download = Download.Idle,
)

class UpdatesViewModel(
    private val monitor: UpdateMonitor,
    private val installer: UpdateInstaller,
    private val prefs: PrefsRepository,
    private val openWith: OpenWith,
    private val notices: NoticeCenter,
    private val navigator: Navigator,
) : ViewModel() {
    private val appVersion: String = BuildConfig.VERSION_NAME
    private val download = MutableStateFlow<Download>(Download.Idle)
    private var downloadJob: Job? = null

    val uiState: StateFlow<UpdatesUiState> = combine(prefs.prefs.map { it.updates }.distinctUntilChanged(), monitor.lastError, download, ::build)
        .uiState(viewModelScope, build(prefs.prefs.value.updates, monitor.lastError.value, download.value))

    init {
        monitor.acknowledge()
    }

    private fun build(u: UpdatePrefs, lastError: String?, d: Download) = UpdatesUiState(
        appVersion = appVersion,
        buildNumber = BuildConfig.VERSION_CODE.toString(),
        latestVersion = u.latestVersion,
        isUpdateAvailable = monitor.isNewer(u.latestVersion),
        isDailyCheckEnabled = u.isDailyCheckEnabled,
        lastCheckedText = if (u.lastCheckedAt == 0L) "Never" else Format.listTime(u.lastCheckedAt),
        lastError = lastError,
        download = d,
    )

    fun toggleDailyCheck() = prefs.update { it.copy(updates = it.updates.copy(isDailyCheckEnabled = !it.updates.isDailyCheckEnabled)) }

    fun checkForUpdates() {
        viewModelScope.launch {
            val release = notices.withLoading("Checking for updates…") { monitor.check() }
            when {
                release == null -> notices.addError("Couldn't check for updates: ${monitor.lastError.value ?: "no answer"}")
                monitor.isNewer(release.version) -> notices.setPrompt(
                    Prompt(
                        message = "Version ${release.version} is available. You have $appVersion.",
                        leftLabel = "Later",
                        rightLabel = "Download",
                        onRight = { downloadAndInstall() },
                    ),
                )
                else -> notices.setSuccess("You have the latest version ($appVersion).")
            }
        }
    }

    /** Fetches the release's APK for this device, then hands it to the installer; a download in flight is left alone. */
    fun downloadAndInstall() {
        if (download.value is Download.Running) return
        (download.value as? Download.Ready)?.let { ready ->
            install(ready.file)
            return
        }
        downloadJob = viewModelScope.launch {
            try {
                val release = monitor.latestRelease.value ?: monitor.check()
                    ?: throw IllegalStateException(monitor.lastError.value ?: "The release could not be read")
                val url = release.apkUrl ?: throw IllegalStateException("This release has no APK for your device; use the releases page")
                val name = release.apkName ?: "telenebula-${release.version}.apk"
                download.value = Download.Running(0f)
                installer.download(url, name, release.apkBytes).collect { step ->
                    when (step) {
                        is DownloadStep.Progress -> download.update { Download.Running(step.fraction) }
                        is DownloadStep.Done -> {
                            download.value = Download.Ready(step.file)
                            install(step.file)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                download.value = Download.Idle
                notices.addError("Couldn't download the update: ${e.userMessage()}")
            }
        }
    }

    private fun install(file: File) {
        try {
            installer.install(file)
        } catch (e: Exception) {
            notices.addError("Couldn't open the installer: ${e.userMessage()}")
        }
    }

    fun openReleases() = openWith.openUrl(Links.RELEASES_URL)

    fun goBack() {
        navigator.pop()
    }

    override fun onCleared() {
        downloadJob?.cancel()
        super.onCleared()
    }
}

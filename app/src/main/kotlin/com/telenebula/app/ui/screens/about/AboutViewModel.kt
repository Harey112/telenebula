package com.telenebula.app.ui.screens.about

import androidx.lifecycle.ViewModel
import com.telenebula.app.BuildConfig
import com.telenebula.app.nav.Navigator
import com.telenebula.app.platform.Links
import com.telenebula.app.platform.OpenWith

class License(val name: String, val license: String)

class AboutViewModel(private val openWith: OpenWith, private val navigator: Navigator) : ViewModel() {
    val appVersion: String = BuildConfig.VERSION_NAME
    val licenses: List<License> = LICENSES

    fun openDocs() = openWith.openUrl(Links.DOCS_URL)
    fun openIssues() = openWith.openUrl(Links.ISSUES_URL)
    fun goBack() = navigator.pop()

    private companion object {
        val LICENSES = listOf(
            License("Nebula (slackhq) & mobile_nebula (DefinedNet)", "MIT"),
            License("Jetpack Compose, AndroidX", "Apache-2.0"),
            License("libwebrtc (stream-webrtc-android build)", "BSD-3 / Apache-2.0"),
            License("lucide icons", "ISC"),
            License("kotlinx.coroutines, kotlinx.serialization", "Apache-2.0"),
            License("Coil, Media3, CameraX, ML Kit barcode, ZXing", "Apache-2.0"),
        )
    }
}

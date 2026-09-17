package com.telenebula.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.sheets.MediaViewerCenter
import com.telenebula.app.ui.fragments.MediaViewer

/** Root media viewer, mounted once below the notices and the lock gate. */
@Composable
fun MediaViewerHost(center: MediaViewerCenter) {
    val target by center.target.collectAsStateWithLifecycle()
    MediaViewer(target = target, onClose = center::close, onSave = center::saveToGallery)
}

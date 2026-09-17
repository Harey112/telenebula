package com.telenebula.app.sheets

import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.notices.reporting
import com.telenebula.app.platform.AttachmentStore
import com.telenebula.app.ui.fragments.MediaSource
import com.telenebula.app.ui.fragments.MediaViewerTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The full-screen media viewer, drawn once at the root so the lock gate and the notices always sit above it. */
class MediaViewerCenter(
    private val files: AttachmentStore,
    private val notices: NoticeCenter,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow<MediaViewerTarget?>(null)
    val target: StateFlow<MediaViewerTarget?> = mutable.asStateFlow()

    fun open(target: MediaViewerTarget) {
        mutable.value = target
    }

    fun close() {
        mutable.value = null
    }

    fun saveToGallery() {
        val target = mutable.value ?: return
        scope.launch {
            notices.reporting("Couldn't save to the gallery") {
                val saved = when (target) {
                    is MediaViewerTarget.Video -> files.saveToGallery(target.file, "video/*")
                    is MediaViewerTarget.Image -> when (val source = target.source) {
                        is MediaSource.Path -> files.saveToGallery(source.file, "image/*")
                        is MediaSource.Inline -> false
                    }
                }
                if (saved) notices.setSuccess("Saved to gallery: ${target.name}") else notices.addError("Couldn't save to the gallery.")
            }
        }
    }
}

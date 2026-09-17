package com.telenebula.app.ui.shared

import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.platform.OpenWith
import com.telenebula.app.ui.fragments.MediaSource
import com.telenebula.app.ui.fragments.MediaViewerTarget
import com.telenebula.app.ui.fragments.mediaSource
import com.telenebula.core.model.ChatMessage
import com.telenebula.core.model.MessageKind
import kotlinx.coroutines.CancellationException

/** Media answers with a viewer target, anything else goes to the chooser; null means nothing to show. */
fun openAttachment(msg: ChatMessage, openWith: OpenWith, notices: NoticeCenter): MediaViewerTarget? {
    val att = msg.attachment
    val source = att?.mediaSource() as? MediaSource.Path
    if (att == null || source == null) {
        notices.addWarning("File unavailable: This file is no longer on this device.")
        return null
    }
    return when (msg.kind) {
        MessageKind.IMAGE -> MediaViewerTarget.Image(source, att.name)
        MessageKind.VIDEO -> MediaViewerTarget.Video(source.file, att.name)
        else -> {
            try {
                openWith.openFile(source.file, att.mime)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.addWarning("Can't open: No app on this phone can open that file type.")
            }
            null
        }
    }
}

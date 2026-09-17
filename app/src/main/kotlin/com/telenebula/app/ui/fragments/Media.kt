package com.telenebula.app.ui.fragments

import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.telenebula.app.ui.icons.Icon
import com.telenebula.app.ui.icons.TnIcon
import com.telenebula.app.ui.theme.TnType
import com.telenebula.core.model.MessageAttachment
import java.io.File

/** What an attachment can be loaded from: a file on disk, or (legacy) inline base64 bytes. */
sealed interface MediaSource {
    class Path(val file: File) : MediaSource
    class Inline(val bytes: ByteArray) : MediaSource
}

fun MessageAttachment.mediaSource(): MediaSource? {
    val path = uri
    if (path != null) return MediaSource.Path(File(path.removePrefix("file://")))
    val inline = dataB64 ?: return null
    return MediaSource.Inline(Base64.decode(inline, Base64.DEFAULT))
}

private fun MediaSource.model(): Any = when (this) {
    is MediaSource.Path -> file
    is MediaSource.Inline -> bytes
}

/** Image with the project's fixed fit/transition/cache policy (memory + disk, no fade). */
@Composable
fun CachedImage(
    source: MediaSource,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    /** the decoded pixel size, once it is known; the only ratio that is always right */
    onIntrinsicSize: ((Int, Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val request = remember(source) { ImageRequest.Builder(context).data(source.model()).crossfade(false).build() }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        onSuccess = { state -> onIntrinsicSize?.invoke(state.result.image.width, state.result.image.height) },
    )
}

/** Video player with the platform controls; released when it leaves composition. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerView(file: File, modifier: Modifier = Modifier, areControlsShown: Boolean = true) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        update = { view ->
            view.player = player
            // hiding the app's header while the platform bar stayed up would just swap one
            // piece of chrome for another, so the two move together
            view.useController = areControlsShown
            if (!areControlsShown) view.hideController()
        },
    )
}

sealed interface MediaViewerTarget {
    val name: String

    class Image(val source: MediaSource, override val name: String) : MediaViewerTarget
    class Video(val file: File, override val name: String) : MediaViewerTarget
}

/** Full-screen viewer for image and video attachments, drawn in-tree so the lock gate and the secure flag cover it; always dark over media. */
@Composable
fun MediaViewer(target: MediaViewerTarget?, onClose: () -> Unit, onSave: () -> Unit) {
    if (target == null) return
    BackHandler(onBack = onClose)
    run {
        // purely local to this overlay: it resets every time the viewer is opened, and nothing
        // outside the viewer has any reason to know whether the chrome happens to be up
        var areControlsShown by remember(target) { mutableStateOf(true) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = if (areControlsShown) "Hide controls" else "Show controls",
                ) { areControlsShown = !areControlsShown },
        ) {
            when (target) {
                is MediaViewerTarget.Image -> CachedImage(target.source, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit, contentDescription = target.name)
                is MediaViewerTarget.Video -> VideoPlayerView(target.file, modifier = Modifier.fillMaxSize(), areControlsShown = areControlsShown)
            }
            AnimatedVisibility(visible = areControlsShown, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x66000000))
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ViewerButton(TnIcon.CLOSE, "Close viewer", onClose)
                    Text(target.name, style = TnType.body, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    ViewerButton(TnIcon.FOLDER, "Save to gallery", onSave, size = 20.dp)
                }
                }
        }
    }
}

@Composable
private fun ViewerButton(icon: TnIcon, label: String, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 22.dp) {
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0x22FFFFFF)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, tint = Color.White, size = size, contentDescription = label) }
}

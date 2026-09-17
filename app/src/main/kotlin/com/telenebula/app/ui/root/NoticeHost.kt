package com.telenebula.app.ui.root

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.ui.fragments.ErrorsCard
import com.telenebula.app.ui.fragments.LoadingCard
import com.telenebula.app.ui.fragments.PromptCard
import com.telenebula.app.ui.fragments.SuccessCard
import com.telenebula.app.ui.fragments.WarningCard
import com.telenebula.app.ui.theme.TnSpace
import com.telenebula.app.ui.theme.TnTheme

/**
 * Root notices. Cards stack bottom → top: success, prompt, one warning at a time, every error in
 * one card, then loading on top (non-dismissable). Cards further back shrink and fade a little.
 * Drawn last in the root Box so it covers sheets and the in-app call window.
 */
@Composable
fun NoticeHost(center: NoticeCenter) {
    val notices by center.state.collectAsStateWithLifecycle()
    var promptValue by remember { mutableStateOf("") }
    val colors = TnTheme.colors
    val step = with(LocalDensity.current) { 8.dp.toPx() }

    val hasPrompt = notices.prompt != null
    val hasWarning = notices.warnings.isNotEmpty()
    val hasError = notices.errors.isNotEmpty()
    val behindSuccess = (if (hasPrompt) 1 else 0) + (if (hasWarning) 1 else 0) + (if (hasError) 1 else 0)
    val behindPrompt = (if (hasWarning) 1 else 0) + (if (hasError) 1 else 0)

    AnimatedVisibility(visible = !notices.isEmpty, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
        if (notices.loading == null) {
            BackHandler {
                when {
                    hasError -> center.clearErrors()
                    hasWarning -> center.popWarning()
                    hasPrompt -> {
                        notices.prompt?.onLeft?.invoke()
                        promptValue = ""
                        center.clearPrompt()
                    }
                    notices.success != null -> center.clearSuccess()
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.overlay)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .imePadding()
                .padding(horizontal = TnSpace.xl),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.fillMaxWidth().widthIn(max = 400.dp), contentAlignment = Alignment.Center) {
                notices.success?.let { success ->
                    Box(
                        modifier = Modifier
                            .zIndex(1f)
                            .graphicsLayer {
                                translationY = -step * behindSuccess
                                scaleX = 0.94f
                                scaleY = 0.94f
                                alpha = if (behindSuccess > 0) 0.7f else 1f
                            },
                    ) { SuccessCard(success.message, onDismiss = center::clearSuccess) }
                }
                notices.prompt?.let { prompt ->
                    Box(
                        modifier = Modifier
                            .zIndex(2f)
                            .graphicsLayer {
                                translationY = -step * behindPrompt
                                scaleX = 0.96f
                                scaleY = 0.96f
                                alpha = if (behindPrompt > 0) 0.8f else 1f
                            },
                    ) {
                        PromptCard(
                            prompt = prompt,
                            value = promptValue,
                            onValueChange = { promptValue = it },
                            onCancel = {
                                prompt.onLeft()
                                promptValue = ""
                                center.clearPrompt()
                            },
                            onConfirm = {
                                val entered = promptValue
                                promptValue = ""
                                center.clearPrompt()
                                prompt.onRight(entered)
                            },
                            autoFocus = !hasWarning && !hasError,
                        )
                    }
                }
                if (hasWarning) {
                    Box(
                        modifier = Modifier
                            .zIndex(3f)
                            .graphicsLayer {
                                translationY = if (hasError) -step else 0f
                                scaleX = if (hasError) 0.98f else 1f
                                scaleY = if (hasError) 0.98f else 1f
                                alpha = if (hasError) 0.9f else 1f
                            },
                    ) { WarningCard(notices.warnings.first(), notices.warnings.size, onDismiss = center::popWarning) }
                }
                if (hasError) {
                    Box(modifier = Modifier.zIndex(4f)) { ErrorsCard(notices.errors, onDismiss = center::clearErrors) }
                }
                notices.loading?.let { message ->
                    Box(modifier = Modifier.zIndex(5f)) { LoadingCard(message) }
                }
            }
        }
    }
}

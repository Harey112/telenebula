package com.telenebula.app.nav

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay

private const val FADE_MS = 120

private val fade: ContentTransform = fadeIn(tween(FADE_MS)) togetherWith fadeOut(tween(FADE_MS))
private val none: ContentTransform = EnterTransition.None togetherWith ExitTransition.None

/** Metadata for entries that must not animate (tab roots and the setup screen). */
val noAnimation: Map<String, Any> = NavDisplay.transitionSpec { none } + NavDisplay.popTransitionSpec { none }

/**
 * The one NavDisplay: a 120 ms cross-fade between screens (tab switches are instant), a
 * ViewModel store and saveable state per entry, back handled by the navigator. Only the top
 * entry composes, so inactive screens cost nothing — the RN `freezeOnBlur` for free.
 */
@Composable
fun TnNavDisplay(navigator: Navigator, modifier: Modifier = Modifier, entries: EntryProviderScope<TnKey>.() -> Unit) {
    NavDisplay(
        backStack = navigator.backStack,
        modifier = modifier,
        onBack = { navigator.pop() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = { fade },
        popTransitionSpec = { fade },
        predictivePopTransitionSpec = { _ -> fade },
        entryProvider = entryProvider(builder = entries),
    )
}

package com.telenebula.app.ui.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The one sharing policy for a screen's state. [seed] must be built from the same inputs the flow
 * combines, read synchronously: a blank default flashes the wrong frame on every revisit, because
 * the flow is torn down [UI_STOP_TIMEOUT_MS] after the last subscriber leaves and rebuilt on the next.
 */
fun <T> Flow<T>.uiState(scope: CoroutineScope, seed: T): StateFlow<T> =
    stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_TIMEOUT_MS), seed)

/** a tab switch and back costs nothing; anything longer away starts from the seed again */
const val UI_STOP_TIMEOUT_MS = 5_000L

package com.telenebula.app.ui.fragments

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A floating box that pans freely and settles inside [bounds]; the self-view also snaps to a corner. */
@Stable
class FloatingBoxState(initial: Offset, private val scope: CoroutineScope) {
    val offset = Animatable(initial, Offset.VectorConverter)
    var bounds: Rect = Rect.Zero
    var snapToCorners: Boolean = false

    fun drag(delta: Offset) {
        scope.launch { offset.snapTo(offset.value + delta) }
    }

    fun settle() {
        val b = bounds
        if (b.isEmpty) return
        val current = offset.value
        val x = if (snapToCorners) (if (current.x < (b.left + b.right) / 2) b.left else b.right) else current.x.coerceIn(b.left, maxOf(b.left, b.right))
        val y = current.y.coerceIn(b.top, maxOf(b.top, b.bottom))
        scope.launch { offset.animateTo(Offset(x, y), spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) }
    }
}

@Composable
fun rememberFloatingBoxState(initial: Offset): FloatingBoxState {
    val scope = rememberCoroutineScope()
    return remember { FloatingBoxState(initial, scope) }
}

/** Drag anywhere; on release the box settles (clamped or snapped to the nearest corner). */
fun Modifier.floatingDrag(state: FloatingBoxState): Modifier = pointerInput(state) {
    detectDragGestures(
        onDrag = { change, delta ->
            change.consume()
            state.drag(delta)
        },
        onDragEnd = { state.settle() },
        onDragCancel = { state.settle() },
    )
}

/** Re-settles when the bounds change (rotation, insets), keeping the box on screen. */
@Composable
fun FloatingBoxState.SettleOnBounds(bounds: Rect) {
    LaunchedEffect(bounds) {
        this@SettleOnBounds.bounds = bounds
        settle()
    }
}

@Composable
fun touchSlopPx(): Float = LocalViewConfiguration.current.touchSlop

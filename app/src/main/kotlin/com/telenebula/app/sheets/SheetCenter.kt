package com.telenebula.app.sheets

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the root bottom sheet shows: a title and the content. The content is a root component that
 * reads its own data from the graph, so a request captures plain values — an id, a slot — and never
 * a view model; the sheet keeps updating after the screen that opened it is gone.
 */
class SheetRequest(val title: String, val content: @Composable () -> Unit)

class SheetCenter {
    private val mutable = MutableStateFlow<SheetRequest?>(null)
    val request: StateFlow<SheetRequest?> = mutable.asStateFlow()

    fun open(request: SheetRequest) {
        mutable.value = request
    }

    fun close() {
        mutable.value = null
    }
}

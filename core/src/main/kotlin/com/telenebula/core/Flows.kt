package com.telenebula.core

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * `debounce` that lets the first value through at once. A first frame never waits for a quiet
 * period: only the bursts that follow (typing, invalidation storms) are coalesced.
 */
@OptIn(FlowPreview::class)
fun <T> Flow<T>.debounceAfterFirst(timeoutMillis: Long): Flow<T> = flow {
    var isFirst = true
    emitAll(
        this@debounceAfterFirst.debounce {
            if (isFirst) {
                isFirst = false
                0L
            } else {
                timeoutMillis
            }
        },
    )
}

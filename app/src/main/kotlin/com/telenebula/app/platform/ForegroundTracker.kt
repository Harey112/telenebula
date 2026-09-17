package com.telenebula.app.platform

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether any of the app's screens is on display; constructed on the main thread. */
class ForegroundTracker : DefaultLifecycleObserver {
    private val state = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = state.asStateFlow()

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        state.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        state.value = false
    }
}

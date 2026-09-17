package com.telenebula.app.notices

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** A question with two buttons; with [placeholder] it also takes a line of text. */
class Prompt(
    val message: String,
    val placeholder: String? = null,
    val leftLabel: String? = null,
    val rightLabel: String? = null,
    /** the right button confirms something destructive */
    val isDestructive: Boolean = false,
    val onLeft: () -> Unit = {},
    val onRight: (String) -> Unit,
)

class Success(val message: String, val onDismiss: (() -> Unit)? = null)

/** Every notice in the app lives in one of these slots; the root host renders them stacked. */
data class Notices(
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val success: Success? = null,
    val prompt: Prompt? = null,
    val loading: String? = null,
) {
    val isEmpty: Boolean get() = errors.isEmpty() && warnings.isEmpty() && success == null && prompt == null && loading == null
}

/**
 * Root notices. Errors merge into one card, warnings show one at a time in arrival order,
 * success and prompt are single slots, loading sits on top and cannot be dismissed.
 * Safe to call from any thread.
 */
class NoticeCenter {
    private val mutable = MutableStateFlow(Notices())
    val state: StateFlow<Notices> = mutable.asStateFlow()

    fun addError(message: String) = mutable.update { s ->
        if (message in s.errors) s else s.copy(errors = s.errors + message)
    }

    fun clearErrors() = mutable.update { it.copy(errors = emptyList()) }

    fun addWarning(message: String) = mutable.update { s ->
        if (message in s.warnings) s else s.copy(warnings = s.warnings + message)
    }

    fun popWarning() = mutable.update { it.copy(warnings = it.warnings.drop(1)) }

    fun setSuccess(message: String?, onDismiss: (() -> Unit)? = null) =
        mutable.update { it.copy(success = message?.let { m -> Success(m, onDismiss) }) }

    /** Dismisses the success card and runs its callback once. */
    fun clearSuccess() {
        val previous = mutable.getAndUpdate { it.copy(success = null) }.success
        previous?.onDismiss?.invoke()
    }

    fun setPrompt(prompt: Prompt?) = mutable.update { it.copy(prompt = prompt) }

    fun clearPrompt() = mutable.update { it.copy(prompt = null) }

    fun setLoading(message: String?) = mutable.update { it.copy(loading = message) }

    /** Shows [message] while [block] runs, whatever the outcome. */
    suspend fun <T> withLoading(message: String, block: suspend () -> T): T {
        setLoading(message)
        try {
            return block()
        } finally {
            setLoading(null)
        }
    }

    fun clearAll() {
        val previous = mutable.getAndUpdate { Notices() }.success
        previous?.onDismiss?.invoke()
    }
}

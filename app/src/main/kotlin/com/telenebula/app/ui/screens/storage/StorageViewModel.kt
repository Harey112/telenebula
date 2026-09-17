package com.telenebula.app.ui.screens.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telenebula.app.nav.Navigator
import com.telenebula.app.notices.NoticeCenter
import com.telenebula.app.notices.Prompt
import com.telenebula.app.platform.Format
import com.telenebula.app.platform.PrefsRepository
import com.telenebula.core.CoreClient
import com.telenebula.core.model.StorageStats
import com.telenebula.app.ui.shared.uiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

data class StorageUiState(val stats: StorageStats = StorageStats(), val autoCleanOrphans: Boolean = false)

class StorageViewModel(
    private val core: CoreClient,
    private val prefs: PrefsRepository,
    private val notices: NoticeCenter,
    private val navigator: Navigator,
) : ViewModel() {
    private val manual = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val stats = merge(core.storeChanges(), manual).mapLatest { core.storageStats() }

    val uiState: StateFlow<StorageUiState> = combine(stats, prefs.prefs) { s, p -> StorageUiState(s, p.autoCleanOrphans) }
        .uiState(viewModelScope, StorageUiState(autoCleanOrphans = prefs.prefs.value.autoCleanOrphans))

    fun refresh() {
        manual.tryEmit(Unit)
    }

    fun clearOrphans() {
        viewModelScope.launch {
            val freed = notices.withLoading("Removing media no message references…") { core.clearOrphanAttachments() }
            notices.setSuccess("Storage cleaned: ${Format.bytes(freed)} of unreferenced media removed.")
            refresh()
        }
    }

    fun clearAllHistory() = notices.setPrompt(
        Prompt(
            message = "Clear all chat history\n\nDelete every message on this device? Contacts stay.",
            rightLabel = "Delete",
            isDestructive = true,
            onRight = { viewModelScope.launch { core.clearAllHistory(); refresh() } },
        ),
    )

    fun toggleAutoClean() = prefs.update { it.copy(autoCleanOrphans = !it.autoCleanOrphans) }
    fun goBack() = navigator.pop()
}

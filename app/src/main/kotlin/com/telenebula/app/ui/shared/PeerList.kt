package com.telenebula.app.ui.shared

import com.telenebula.app.platform.ContactLabels
import com.telenebula.core.CoreClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/** A screen that lists one thing about one peer: the header name and the rows. */
data class PeerListUiState<T>(val peerName: String = "", val items: List<T> = emptyList())

/** The contact's label from its live row, the items from [items]; seeded from the cached contact and [seed]. */
fun <T> peerListState(ip: String, core: CoreClient, scope: CoroutineScope, items: Flow<List<T>>, seed: List<T>): StateFlow<PeerListUiState<T>> =
    combine(core.contactFlow(ip), items) { contact, list -> PeerListUiState(contact?.let(ContactLabels::chatLabel) ?: ip, list) }
        .uiState(scope, PeerListUiState(core.cachedContact(ip)?.let(ContactLabels::chatLabel) ?: ip, seed))

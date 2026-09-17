package com.telenebula.app.nav

import android.os.Looper
import androidx.annotation.MainThread
import androidx.compose.runtime.snapshotFlow
import com.telenebula.app.BuildConfig
import androidx.navigation3.runtime.NavBackStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The single back stack rendered by NavDisplay plus the saved nested stacks of the inactive tabs,
 * so switching tabs keeps each tab's screens like Expo Router's per-tab stacks did. Owned by the
 * application graph, so it survives Activity recreation; process death starts at the tab root.
 * Main thread only (it is Compose snapshot state).
 */
@MainThread
class Navigator(scope: CoroutineScope) {
    val backStack: NavBackStack<TnKey> = NavBackStack()
    private val saved = HashMap<Tab, List<TnKey>>(4)

    /** the visible key, as a flow for root logic that reacts to where the user is */
    val topKey: StateFlow<TnKey?> = snapshotFlow { backStack.lastOrNull() }.stateIn(scope, SharingStarted.Eagerly, null)

    val top: TnKey? get() = backStack.lastOrNull()
    val currentTab: Tab? get() = (backStack.firstOrNull() as? TabKey)?.tab
    val isTabBarVisible: Boolean get() = backStack.lastOrNull() is TabKey

    fun push(key: TnKey) {
        requireMain()
        if (backStack.lastOrNull() == key) return
        backStack.add(key)
    }

    /** False when nothing is left to pop (the Activity should finish). */
    fun pop(): Boolean {
        requireMain()
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.size - 1)
        return true
    }

    fun popTo(key: TnKey) {
        val index = backStack.lastIndexOf(key)
        if (index < 0) return
        while (backStack.size > index + 1) backStack.removeAt(backStack.size - 1)
    }

    fun replaceAll(vararg keys: TnKey) {
        requireMain()
        saved.clear()
        backStack.clear()
        backStack.addAll(keys.asList())
    }

    /** Saves the current tab's nested keys, restores the target's; re-tapping the active tab pops to its root. */
    fun switchTab(tab: Tab) {
        requireMain()
        val current = currentTab
        if (current == tab) {
            popToRootKeepingCall()
            return
        }
        val call = backStack.lastOrNull() as? Call
        if (current != null) saved[current] = backStack.drop(1).filterNot { it is Call }
        backStack.clear()
        backStack.add(tab.root())
        saved.remove(tab)?.let(backStack::addAll)
        if (call != null) backStack.add(call)
    }

    /** Deep link or notification: land on the chat with the Chats tab underneath, the call screen kept on top. */
    fun openChat(peerIp: String) {
        requireMain()
        val call = backStack.lastOrNull() as? Call
        if (currentTab != Tab.CHATS) {
            currentTab?.let { saved[it] = backStack.drop(1).filterNot { k -> k is Call } }
            saved.remove(Tab.CHATS)
        }
        backStack.clear()
        backStack.add(ChatsTab)
        backStack.add(Chat(peerIp))
        if (call != null) backStack.add(call)
    }

    /** The call screen only ever sits on top and is never saved in a tab. */
    fun openCall() {
        if (backStack.lastOrNull() is Call) return
        if (backStack.isEmpty()) return
        backStack.add(Call)
    }

    fun closeCall() {
        val index = backStack.lastIndexOf(Call)
        if (index >= 0) backStack.removeAt(index)
    }

    /** `router.dismissAll()`: back to the current tab's root. */
    fun dismissToTabRoot() = popToRootKeepingCall()

    private fun requireMain() {
        if (!BuildConfig.DEBUG) return
        val main = Looper.getMainLooper() ?: return
        check(main.isCurrentThread) { "Navigator is main-thread only" }
    }

    private fun popToRootKeepingCall() {
        val call = backStack.lastOrNull() as? Call
        while (backStack.size > 1) backStack.removeAt(backStack.size - 1)
        if (call != null) backStack.add(call)
    }
}

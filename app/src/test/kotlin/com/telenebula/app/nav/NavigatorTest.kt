package com.telenebula.app.nav

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The back stack's one invariant: the call screen only ever sits on top and is never saved in a tab. */
class NavigatorTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val navigator = Navigator(scope).also { it.replaceAll(ChatsTab) }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `push, pop and popTo`() {
        navigator.push(Chat("fd::1"))
        navigator.push(ChatSettings("fd::1"))
        navigator.push(ChatSettings("fd::1"))
        assertEquals(listOf<TnKey>(ChatsTab, Chat("fd::1"), ChatSettings("fd::1")), navigator.backStack.toList())
        assertTrue(navigator.pop())
        navigator.popTo(ChatsTab)
        assertEquals(listOf<TnKey>(ChatsTab), navigator.backStack.toList())
        assertFalse("the root never pops", navigator.pop())
    }

    @Test
    fun `switching tabs saves and restores each tab's own screens`() {
        navigator.push(Chat("fd::1"))
        navigator.switchTab(Tab.CONTACTS)
        assertEquals(listOf<TnKey>(Tab.CONTACTS.root()), navigator.backStack.toList())
        navigator.push(NewContact)
        navigator.switchTab(Tab.CHATS)
        assertEquals(listOf<TnKey>(ChatsTab, Chat("fd::1")), navigator.backStack.toList())
        navigator.switchTab(Tab.CONTACTS)
        assertEquals(listOf<TnKey>(Tab.CONTACTS.root(), NewContact), navigator.backStack.toList())
        navigator.switchTab(Tab.CONTACTS)
        assertEquals("re-tapping the active tab pops to its root", listOf<TnKey>(Tab.CONTACTS.root()), navigator.backStack.toList())
    }

    @Test
    fun `the call screen stays on top through tab switches and deep links, and is never saved`() {
        navigator.push(Chat("fd::1"))
        navigator.openCall()
        navigator.openCall()
        assertEquals(Call, navigator.top)
        navigator.switchTab(Tab.ME)
        assertEquals(listOf<TnKey>(Tab.ME.root(), Call), navigator.backStack.toList())
        navigator.openChat("fd::9")
        assertEquals(listOf<TnKey>(ChatsTab, Chat("fd::9"), Call), navigator.backStack.toList())
        navigator.closeCall()
        assertEquals(listOf<TnKey>(ChatsTab, Chat("fd::9")), navigator.backStack.toList())
        navigator.switchTab(Tab.ME)
        navigator.switchTab(Tab.CHATS)
        assertFalse("a saved stack never contains the call", navigator.backStack.contains(Call))
    }

    @Test
    fun `dismissing to the tab root keeps only the root and a live call`() {
        navigator.push(Chat("fd::1"))
        navigator.push(ChatSettings("fd::1"))
        navigator.openCall()
        navigator.dismissToTabRoot()
        assertEquals(listOf<TnKey>(ChatsTab, Call), navigator.backStack.toList())
        assertTrue(navigator.isTabBarVisible.not())
    }
}

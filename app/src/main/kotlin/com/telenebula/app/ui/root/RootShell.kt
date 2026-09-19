package com.telenebula.app.ui.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telenebula.app.LocalAppGraph
import com.telenebula.app.MainActivity
import com.telenebula.app.nav.About
import com.telenebula.app.nav.Account
import com.telenebula.app.nav.Appearance
import com.telenebula.app.nav.ArchivedChats
import com.telenebula.app.nav.Blocked
import com.telenebula.app.nav.Call
import com.telenebula.app.nav.CallPrefs
import com.telenebula.app.nav.CallsTab
import com.telenebula.app.nav.Chat
import com.telenebula.app.nav.ChatLinks
import com.telenebula.app.nav.ChatMedia
import com.telenebula.app.nav.ChatNotifications
import com.telenebula.app.nav.ChatPrefs
import com.telenebula.app.nav.ChatSettings
import com.telenebula.app.nav.ChatsTab
import com.telenebula.app.nav.Contact
import com.telenebula.app.nav.ContactCalls
import com.telenebula.app.nav.ContactsTab
import com.telenebula.app.nav.Diagnostics
import com.telenebula.app.nav.Lighthouse
import com.telenebula.app.nav.Network
import com.telenebula.app.nav.NewContact
import com.telenebula.app.nav.NewMessage
import com.telenebula.app.nav.NotificationPrefs
import com.telenebula.app.nav.Privacy
import com.telenebula.app.nav.RenewCertificate
import com.telenebula.app.nav.MeTab
import com.telenebula.app.nav.Settings
import com.telenebula.app.nav.Dex
import com.telenebula.app.nav.Status
import com.telenebula.app.nav.TabKey
import com.telenebula.app.nav.Setup
import com.telenebula.app.nav.Storage
import com.telenebula.app.nav.TnNavDisplay
import com.telenebula.app.nav.Updates
import com.telenebula.app.nav.noAnimation
import androidx.navigation3.ui.NavDisplay
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import com.telenebula.app.ui.screens.about.AboutScreen
import com.telenebula.app.ui.screens.about.AboutViewModel
import com.telenebula.app.ui.screens.account.AccountScreen
import com.telenebula.app.ui.screens.account.AccountViewModel
import com.telenebula.app.ui.screens.appearance.AppearanceScreen
import com.telenebula.app.ui.screens.appearance.AppearanceViewModel
import com.telenebula.app.ui.screens.blocked.BlockedScreen
import com.telenebula.app.ui.screens.blocked.BlockedViewModel
import com.telenebula.app.ui.screens.callprefs.CallPrefsScreen
import com.telenebula.app.ui.screens.callprefs.CallPrefsViewModel
import com.telenebula.app.ui.screens.chatprefs.ChatPrefsScreen
import com.telenebula.app.ui.screens.chatprefs.ChatPrefsViewModel
import com.telenebula.app.ui.screens.notifications.NotificationsScreen
import com.telenebula.app.ui.screens.notifications.NotificationsViewModel
import com.telenebula.app.ui.screens.privacy.PrivacyScreen
import com.telenebula.app.ui.screens.privacy.PrivacyViewModel
import com.telenebula.app.ui.screens.renew.RenewCertificateScreen
import com.telenebula.app.ui.screens.renew.RenewCertificateViewModel
import com.telenebula.app.ui.screens.storage.StorageScreen
import com.telenebula.app.ui.screens.storage.StorageViewModel
import com.telenebula.app.ui.screens.updates.UpdatesScreen
import com.telenebula.app.ui.screens.updates.UpdatesViewModel
import com.telenebula.calls.system.OverlayPermission
import android.content.Intent
import com.telenebula.app.ui.screens.archived.ArchivedChatsScreen
import com.telenebula.app.ui.screens.archived.ArchivedChatsViewModel
import com.telenebula.app.ui.screens.chats.ChatsScreen
import com.telenebula.app.ui.screens.chats.ChatsViewModel
import com.telenebula.app.ui.screens.newmessage.NewMessageScreen
import com.telenebula.app.ui.screens.newmessage.NewMessageViewModel
import com.telenebula.app.ui.screens.contacts.ContactsScreen
import com.telenebula.app.ui.screens.contacts.ContactsViewModel
import com.telenebula.app.ui.screens.diagnostics.DiagnosticsScreen
import com.telenebula.app.ui.screens.diagnostics.DiagnosticsViewModel
import com.telenebula.app.ui.screens.lighthouse.LighthouseScreen
import com.telenebula.app.ui.screens.lighthouse.LighthouseViewModel
import com.telenebula.app.ui.screens.network.NetworkScreen
import com.telenebula.app.ui.screens.network.NetworkViewModel
import com.telenebula.app.ui.screens.newcontact.NewContactScreen
import com.telenebula.app.ui.screens.newcontact.NewContactViewModel
import com.telenebula.app.ui.screens.contact.ContactScreen
import com.telenebula.app.ui.screens.contact.ContactViewModel
import com.telenebula.app.ui.screens.contactcalls.ContactCallsScreen
import com.telenebula.app.ui.screens.contactcalls.ContactCallsViewModel
import com.telenebula.app.ui.screens.calls.CallsScreen
import com.telenebula.app.ui.screens.calls.CallsViewModel
import com.telenebula.app.ui.screens.chat.ChatScreen
import com.telenebula.app.ui.screens.chat.ChatViewModel
import com.telenebula.app.ui.screens.chatlinks.ChatLinksScreen
import com.telenebula.app.ui.screens.chatlinks.ChatLinksViewModel
import com.telenebula.app.ui.screens.chatmedia.ChatMediaScreen
import com.telenebula.app.ui.screens.chatmedia.ChatMediaViewModel
import com.telenebula.app.ui.screens.chatnotifications.ChatNotificationsScreen
import com.telenebula.app.ui.screens.chatnotifications.ChatNotificationsViewModel
import com.telenebula.app.ui.screens.chatsettings.ChatSettingsScreen
import com.telenebula.app.ui.screens.chatsettings.ChatSettingsViewModel
import com.telenebula.app.ui.screens.call.CallScreen
import com.telenebula.app.ui.screens.call.CallViewModel
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import com.telenebula.app.ui.screens.settings.SettingsScreen
import com.telenebula.app.ui.screens.settings.SettingsViewModel
import com.telenebula.app.ui.screens.me.MeScreen
import com.telenebula.app.ui.screens.me.MeViewModel
import com.telenebula.app.ui.screens.dex.DexScreen
import com.telenebula.app.ui.screens.dex.DexViewModel
import com.telenebula.app.ui.screens.status.StatusScreen
import com.telenebula.app.ui.screens.status.StatusViewModel
import com.telenebula.app.ui.screens.setup.SetupScreen
import com.telenebula.app.ui.screens.setup.SetupViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.telenebula.app.ui.theme.TnTheme

/**
 * Everything under the theme: the navigation display, the tab bar, then the root overlays in
 * z-order — sheet, notices, app-lock cover. Notices are drawn last so they cover everything.
 */
@Composable
fun RootShell(activity: MainActivity) {
    val graph = LocalAppGraph.current
    val prefs by graph.prefs.prefs.collectAsStateWithLifecycle()
    val navigator = graph.navigator
    val isLocked by graph.appLock.isLocked.collectAsStateWithLifecycle()
    val topKey by navigator.topKey.collectAsStateWithLifecycle()
    // only the banner's presence matters here; the once-a-second call timer must not recompose the shell
    val bannerFlow = remember(graph) { graph.presence.state.map { it.isOffCallScreen && !it.hasVideo }.distinctUntilChanged() }
    val hasBanner by bannerFlow.collectAsStateWithLifecycle(initialValue = false)

    LaunchedEffect(Unit) { graph.root.start(activity.launchRequests) }

    TnTheme(themeMode = prefs.themeMode, colorTheme = prefs.colorTheme, customAccent = prefs.customAccent) {
        val colors = TnTheme.colors
        Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
            Column(modifier = Modifier.fillMaxSize()) {
                CallBanner(graph.presence.state, onOpen = navigator::openCall)
                Box(modifier = Modifier.fillMaxWidth().weight(1f).then(if (hasBanner) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
                    if (navigator.backStack.isNotEmpty()) {
                        TnNavDisplay(navigator) {
                            entry<Setup>(metadata = noAnimation) {
                                SetupScreen(
                                    viewModel {
                                        SetupViewModel(graph.gateway, graph.attachments, graph.certs, graph.identity, graph.runtime, graph.nebulaDraft, graph.notices)
                                    },
                                )
                            }
                            entry<ChatsTab>(metadata = noAnimation) { ChatsScreen(viewModel { ChatsViewModel(graph.core, graph.runtime, graph.notices, navigator) }) }
                            entry<ContactsTab>(metadata = noAnimation) { ContactsScreen(viewModel { ContactsViewModel(graph.core, graph.notices, navigator) }) }
                            entry<CallsTab>(metadata = noAnimation) { CallsScreen(viewModel { CallsViewModel(graph.core, graph.runtime, graph.callEngine, graph.gateway, graph.notices, navigator) }) }
                            entry<MeTab>(metadata = noAnimation) {
                                MeScreen(viewModel { MeViewModel(graph.runtime, graph.core, graph.notices, graph.updateMonitor, navigator) })
                            }
                            entry<Settings> { SettingsScreen(viewModel { SettingsViewModel(graph.runtime, graph.updateMonitor, navigator) }) }
                            entry<Dex> { DexScreen(viewModel { DexViewModel(navigator) }) }
                            entry<Status> { StatusScreen(viewModel { StatusViewModel(graph.prefs, graph.runtime, navigator) }) }
                            entry<Call>(metadata = NavDisplay.transitionSpec { fadeIn(tween(120)) togetherWith ExitTransition.None }) {
                                CallScreen(
                                    viewModel {
                                        CallViewModel(graph.callEngine, graph.presence, activity.launchRequests, graph.gateway, graph.notices, { graph.gateway.startActivity { OverlayPermission.settingsIntent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } }, navigator)
                                    },
                                )
                            }
                            entry<Chat> { key ->
                                ChatScreen(
                                    viewModel(key = key.peerIp) {
                                        ChatViewModel(
                                            key.peerIp, activity.applicationContext, graph.core, graph.runtime, graph.prefs, graph.typing, graph.peerPresence, graph.transfers,
                                            graph.peerQueues, graph.chatSearch, graph.gateway, graph.appLock, graph.attachments, graph.openWith, graph.viewer, graph.voicePlayer, graph.voiceRecorder, graph.callEngine, graph.sheets,
                                            graph.notices, navigator,
                                        )
                                    },
                                )
                            }
                            entry<ChatSettings> { key -> ChatSettingsScreen(viewModel(key = key.peerIp) { ChatSettingsViewModel(key.peerIp, graph.core, graph.attachments, graph.openWith, graph.viewer, graph.peerQueues, graph.chatSearch, graph.appLock, graph.notices, navigator) }) }
                            entry<ChatMedia> { key -> ChatMediaScreen(viewModel(key = key.peerIp) { ChatMediaViewModel(key.peerIp, graph.core, graph.openWith, graph.viewer, graph.notices, navigator) }) }
                            entry<ChatLinks> { key -> ChatLinksScreen(viewModel(key = key.peerIp) { ChatLinksViewModel(key.peerIp, graph.core, graph.openWith, graph.notices, navigator) }) }
                            entry<ChatNotifications> { key -> ChatNotificationsScreen(viewModel(key = key.peerIp) { ChatNotificationsViewModel(key.peerIp, graph.core, navigator) }) }
                            entry<NewMessage> { NewMessageScreen(viewModel { NewMessageViewModel(graph.core, navigator) }) }
                            entry<ArchivedChats> { ArchivedChatsScreen(viewModel { ArchivedChatsViewModel(graph.core, graph.notices, navigator) }) }
                            entry<Contact> { key -> ContactScreen(viewModel(key = key.peerIp) { ContactViewModel(key.peerIp, graph.core, graph.peerQueues, graph.peerPresence, graph.prefs, graph.vpn, graph.runtime, graph.callEngine, graph.gateway, graph.notices, navigator) }) }
                            entry<ContactCalls> { key -> ContactCallsScreen(viewModel(key = key.peerIp) { ContactCallsViewModel(key.peerIp, graph.core, navigator) }) }
                            entry<NewContact> { NewContactScreen(viewModel { NewContactViewModel(graph.core, graph.gateway, graph.notices, navigator) }) }
                            entry<Account> { AccountScreen(viewModel { AccountViewModel(graph.runtime, graph.core, graph.identity, graph.prefs, graph.gateway, graph.attachments, graph.openWith, graph.notices, navigator) }) }
                            entry<RenewCertificate> { RenewCertificateScreen(viewModel { RenewCertificateViewModel(graph.runtime, graph.gateway, graph.attachments, graph.certs, graph.identity, graph.notices, navigator) }) }
                            entry<Appearance> { AppearanceScreen(viewModel { AppearanceViewModel(graph.prefs, navigator) }) }
                            entry<Privacy> { PrivacyScreen(viewModel { PrivacyViewModel(graph.prefs, graph.core, graph.runtime, graph.appLock, navigator) }) }
                            entry<Blocked> { BlockedScreen(viewModel { BlockedViewModel(graph.core, navigator) }) }
                            entry<Network> { NetworkScreen(viewModel { NetworkViewModel(graph.core, graph.vpn, graph.runtime, graph.prefs, graph.notices, navigator) }) }
                            entry<Lighthouse> { LighthouseScreen(viewModel { LighthouseViewModel(graph.runtime, graph.identity, graph.nebulaDraft, graph.notices, navigator) }) }
                            entry<Diagnostics> {
                                DiagnosticsScreen(viewModel { DiagnosticsViewModel(graph.core, graph.peerQueues, graph.vpn, graph.runtime, graph.callEngine, graph.attachments, graph.openWith, graph.notices, navigator) })
                            }
                            entry<ChatPrefs> { ChatPrefsScreen(viewModel { ChatPrefsViewModel(graph.prefs, graph.sheets, navigator) }) }
                            entry<CallPrefs> {
                                CallPrefsScreen(
                                    viewModel {
                                        CallPrefsViewModel(graph.prefs, activity.launchRequests, { graph.gateway.startActivity { OverlayPermission.settingsIntent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } }, navigator)
                                    },
                                )
                            }
                            entry<NotificationPrefs> { NotificationsScreen(viewModel { NotificationsViewModel(graph.prefs, graph.openWith, navigator) }) }
                            entry<Storage> { StorageScreen(viewModel { StorageViewModel(graph.core, graph.prefs, graph.notices, navigator) }) }
                            entry<About> { AboutScreen(viewModel { AboutViewModel(graph.openWith, navigator) }) }
                            entry<Updates> { UpdatesScreen(viewModel { UpdatesViewModel(graph.updateMonitor, graph.installer, graph.prefs, graph.openWith, graph.notices, navigator) }) }
                        }
                    }
                }
                if (topKey is TabKey) {
                    TabBar(current = navigator.currentTab, onSelect = navigator::switchTab)
                }
            }
            MediaViewerHost(graph.viewer)
            SheetHost()
            NoticeHost(graph.notices)
            AppLockGate(isLocked = isLocked, onRetry = { graph.appLock.prompt(activity) })
        }
    }
}


import { For, Show, Switch, Match } from "solid-js";
import { useDex } from "../../store/context";
import type { Tab } from "../../store";
import { Icon, type IconName } from "../icons.gen";
import ChatList from "../chats/ChatList";
import Conversation from "../chats/Conversation";
import Contacts from "../contacts/Contacts";
import BrowserSettings from "../settings/BrowserSettings";
import Calls from "../calls/Calls";
import CallOverlay from "../calls/CallOverlay";
import Toasts from "../kit/Toasts";
import "./Shell.css";

const tabs: { id: Tab; label: string; icon: IconName }[] = [
  { id: "chats", label: "Chats", icon: "chats" },
  { id: "contacts", label: "Contacts", icon: "contacts" },
  { id: "calls", label: "Calls", icon: "call" },
  { id: "settings", label: "Settings", icon: "settings" },
];

export default function Shell() {
  const runtime = useDex();
  return (
    <div class="dex-shell">
      <aside class="dex-rail">
        <div class="dex-brand"><Icon name="desktop" size={22} /><span>Dex</span></div>
        <nav aria-label="Main navigation">
          <For each={tabs}>{(tab) =>
            <button type="button" class="dex-rail-item" classList={{ active: runtime.state.tab === tab.id }}
              aria-current={runtime.state.tab === tab.id ? "page" : undefined}
              onClick={() => runtime.setTab(tab.id)}>
              <Icon name={tab.icon} size={20} /><span>{tab.label}</span>
              <Show when={tab.id === "chats" && runtime.state.chats.some((chat) => !chat.isArchived && (chat.unread ?? 0) > 0)}>
                <span class="dex-rail-dot" aria-label="Unread chats" />
              </Show>
            </button>
          }</For>
        </nav>
        <div class="dex-rail-foot">
          <div class="dex-identity"><strong>{runtime.state.me?.name}</strong><span>{runtime.state.me?.ip}</span></div>
          <div class="dex-connection" role="status">
            <span class="dex-connection-dot" data-status={runtime.state.connection} />
            <span>{runtime.state.connection === "connected" ? "Connected" : runtime.state.connection === "reconnecting" ? "Reconnecting…" : "Connecting…"}</span>
          </div>
          <button class="dex-sign-out" type="button" aria-label="Log out" onClick={() => void runtime.signOut()}>
            <Icon name="logout" size={20} />
          </button>
        </div>
      </aside>
      <main class="dex-main" data-chat-open={runtime.state.openPeer !== null}>
        <Switch>
          <Match when={runtime.state.tab === "chats"}>
            <ChatList />
            <Conversation />
          </Match>
          <Match when={runtime.state.tab === "contacts"}>
            <Contacts />
          </Match>
          <Match when={runtime.state.tab === "calls"}>
            <Calls />
          </Match>
          <Match when={runtime.state.tab === "settings"}>
            <BrowserSettings />
          </Match>
        </Switch>
      </main>
      <CallOverlay />
      <Toasts />
    </div>
  );
}

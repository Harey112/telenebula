import { createMemo, For, Show } from "solid-js";
import { archivedSummary, chatEmptyText, chatRows } from "../../logic/chat-lists";
import { listTime } from "../../logic/format";
import { useDex } from "../../store/context";
import { Icon } from "../icons.gen";
import "./ChatList.css";

export default function ChatList() {
  const runtime = useDex();
  const rows = createMemo(() => chatRows(
    runtime.state.chats, runtime.state.isArchivedView, runtime.state.openPeer, runtime.state.search,
  ));
  const archived = createMemo(() => archivedSummary(runtime.state.chats));

  return (
    <section class="dex-chat-list-pane" aria-label="Chats">
      <header class="dex-list-header">
        <h1>{runtime.state.isArchivedView ? "Archived chats" : "Chats"}</h1>
      </header>
      <label class="dex-chat-search">
        <Icon name="search" size={18} />
        <input type="search" placeholder="Search chats" aria-label="Search chats" value={runtime.state.search}
          onInput={(event) => runtime.actions.setSearch(event.currentTarget.value)} />
      </label>
      <Show when={runtime.state.isArchivedView || archived().count > 0}>
        <button type="button" class="dex-archive-entry"
          onClick={() => runtime.actions.setArchivedView(!runtime.state.isArchivedView)}>
          <Icon name={runtime.state.isArchivedView ? "back" : "archive"} size={18} />
          <span>{runtime.state.isArchivedView ? "All chats" : "Archived chats"}</span>
          <Show when={!runtime.state.isArchivedView}><span class="dex-archive-count">{archived().count}</span></Show>
        </button>
      </Show>
      <div class="dex-chat-list">
        <For each={rows()} fallback={<p class="dex-list-empty">{chatEmptyText(runtime.state.isArchivedView, runtime.state.search, archived().count > 0)}</p>}>
          {(chat) =>
            <button type="button" class="dex-chat-row"
              classList={{ selected: runtime.state.openPeer === chat.peer }}
              onClick={() => runtime.openChat(chat.peer)}>
              <span class="dex-chat-avatar" aria-hidden="true">{chat.label.slice(0, 1).toLocaleUpperCase()}
                <Show when={runtime.state.presence[chat.peer] === "online"}><span class="dex-chat-presence" /></Show>
              </span>
              <span class="dex-chat-row-main">
                <span class="dex-chat-row-top"><strong>{chat.label}</strong><Show when={chat.isPinned}><Icon name="pin" size={13} /></Show><span>{chat.lastTs ? listTime(chat.lastTs) : ""}</span></span>
                <span class="dex-chat-row-bottom"><span classList={{ "dex-chat-typing": runtime.state.typing.includes(chat.peer) }}>{runtime.state.typing.includes(chat.peer) ? "typing…" : chat.lastBody ?? ""}</span>
                  <Show when={chat.isMuted}><Icon name="bell_off" size={14} /></Show>
                  <Show when={(chat.unread ?? 0) > 0}><b aria-label={`${chat.unread ?? 0} unread`}>{chat.unread}</b></Show>
                </span>
              </span>
            </button>
          }
        </For>
      </div>
    </section>
  );
}

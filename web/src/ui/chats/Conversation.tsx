import { createEffect, createMemo, createSignal, For, onCleanup, Show } from "solid-js";
import { revealStep } from "../../logic/chat-rules";
import { canCall } from "../../logic/chat-rules";
import { timeline } from "../../logic/timeline";
import { useDex } from "../../store/context";
import { Icon } from "../icons.gen";
import Dialog from "../kit/Dialog";
import Composer from "./Composer";
import MessageRow from "./MessageRow";
import ChatInfo from "./ChatInfo";
import "./Conversation.css";

export default function Conversation() {
  const runtime = useDex();
  const items = createMemo(() => timeline(runtime.state.view?.messages ?? []));
  const contact = createMemo(() => runtime.state.contacts.find((item) => item.ip === runtime.state.openPeer)
    ?? runtime.state.view?.contact ?? null);
  const [highlighted, setHighlighted] = createSignal<string | null>(null);
  const [isVisible, setVisible] = createSignal(!document.hidden);
  let scroller: HTMLDivElement | undefined;
  let previousHeight = 0;
  let isRestoringOlder = false;
  let requestedFirstId: string | null = null;
  let lastReadId: string | null = null;
  let highlightTimer: number | null = null;
  const onVisibilityChange = () => setVisible(!document.hidden);
  document.addEventListener("visibilitychange", onVisibilityChange);
  onCleanup(() => {
    document.removeEventListener("visibilitychange", onVisibilityChange);
    if (highlightTimer !== null) window.clearTimeout(highlightTimer);
  });

  createEffect(() => {
    const peer = runtime.state.openPeer;
    const messages = runtime.state.view?.messages ?? [];
    const last = messages[messages.length - 1];
    if (requestedFirstId && !runtime.state.isLoadingMore && messages[0]?.id === requestedFirstId) {
      isRestoringOlder = false;
      requestedFirstId = null;
    }
    if (runtime.state.connection !== "connected") lastReadId = null;
    if (isVisible() && runtime.state.connection === "connected" && peer && last
      && messages.some((item) => item.dir === "in" && !item.isRead) && lastReadId !== last.id) {
      if (runtime.send({ t: "mark_read", peer })) lastReadId = last.id;
    }
    if (scroller && last) {
      const shouldStick = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 120 || previousHeight === 0;
      requestAnimationFrame(() => {
        if (!scroller) return;
        if (isRestoringOlder && previousHeight > 0) {
          scroller.scrollTop += scroller.scrollHeight - previousHeight;
          isRestoringOlder = false;
          requestedFirstId = null;
        }
        else if (shouldStick) scroller.scrollTop = scroller.scrollHeight;
        previousHeight = scroller.scrollHeight;
      });
    }
  });

  function loadMore(): void {
    const view = runtime.state.view;
    const first = view?.messages[0];
    if (!view || !first || runtime.state.isLoadingMore) return;
    previousHeight = scroller?.scrollHeight ?? 0;
    if (runtime.send({ t: "load_more", peer: view.peer, beforeTs: first.ts, beforeId: first.id })) {
      isRestoringOlder = true;
      requestedFirstId = first.id;
      runtime.actions.setLoadingMore(true);
    }
  }
  function jumpTo(id: string): void {
    const target = scroller?.querySelector<HTMLElement>(`[data-id="${CSS.escape(id)}"]`);
    if (!target) { runtime.actions.addToast("info", "Load earlier messages to see that reply."); return; }
    target.scrollIntoView({ block: "center", behavior: "smooth" });
    setHighlighted(id);
    if (highlightTimer !== null) window.clearTimeout(highlightTimer);
    highlightTimer = window.setTimeout(() => setHighlighted((current) => current === id ? null : current), 1_600);
  }

  function reveal(id: string): void {
    const peer = runtime.state.openPeer;
    const contact = runtime.state.contacts.find((item) => item.ip === peer) ?? runtime.state.view?.contact;
    switch (revealStep(contact?.revealGate ?? "tap")) {
      case "reveal": runtime.actions.revealMessage(id); break;
      case "confirm": runtime.actions.askReveal(id); break;
      case "on-phone": runtime.actions.addToast("info", "This covered message opens only on your phone."); break;
    }
  }
  return (
    <section class="dex-conversation" aria-label="Conversation">
      <Show when={runtime.state.openPeer} fallback={
        <div class="dex-conversation-empty"><Icon name="chats" size={28} /><p>Choose a chat to start reading.</p></div>
      }>
        <header class="dex-conversation-header">
          <button type="button" class="dex-icon-button dex-chat-back" aria-label="Back to chats" onClick={() => runtime.closeChat()}>
            <Icon name="back" size={20} />
          </button>
          <div><strong>{contact()?.label ?? runtime.state.openPeer}</strong><span>{runtime.state.openPeer}</span></div>
          <div class="dex-conversation-call-actions">
            <button type="button" aria-label="Start voice call" disabled={!canCall(contact(), runtime.state.isTunnelOn, runtime.state.call.phase ?? "idle")}
              onClick={() => { const peer = runtime.state.openPeer; if (peer) runtime.startCall(peer, false); }}><Icon name="call" size={19} /></button>
            <button type="button" aria-label="Start video call" disabled={!canCall(contact(), runtime.state.isTunnelOn, runtime.state.call.phase ?? "idle")}
              onClick={() => { const peer = runtime.state.openPeer; if (peer) runtime.startCall(peer, true); }}><Icon name="video" size={19} /></button>
            <button type="button" aria-label="Chat information" onClick={() => runtime.openChatInfo()}><Icon name="info" size={19} /></button>
          </div>
        </header>
        <div ref={scroller} class="dex-conversation-messages" role="log" aria-label="Messages">
          <Show when={runtime.state.view?.hasMore}><button type="button" class="dex-load-more" disabled={runtime.state.isLoadingMore} onClick={loadMore}>{runtime.state.isLoadingMore ? "Loading…" : "Load earlier messages"}</button></Show>
          <For each={items()} fallback={<p class="dex-conversation-waiting">{runtime.state.view ? "No messages yet." : "Loading chat…"}</p>}>
            {(item) => item.kind === "message" ? <MessageRow message={item.message} onReveal={reveal} onJump={jumpTo} isHighlighted={highlighted() === item.message.id} />
              : item.kind === "day" ? <div class="dex-day-separator">{item.label}</div> : <div class="dex-unread-divider">Unread messages</div>}
          </For>
        </div>
        <Composer />
        <Show when={runtime.state.isChatInfoOpen}><ChatInfo /></Show>
        <Show when={runtime.state.revealPromptId}>
          {(id) => <Dialog title="Reveal message?" onClose={() => runtime.actions.cancelReveal()} actions={
            <>
              <button type="button" onClick={() => runtime.actions.cancelReveal()}>Cancel</button>
              <button type="button" data-primary="true" onClick={() => runtime.actions.revealMessage(id())}>Reveal</button>
            </>
          }><p>This message stays open until you leave this chat.</p></Dialog>}
        </Show>
      </Show>
    </section>
  );
}

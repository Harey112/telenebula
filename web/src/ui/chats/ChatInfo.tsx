import { createMemo, createSignal, For, onCleanup, Show } from "solid-js";
import { attachmentUrl } from "../../net/api";
import { bytes, stamp } from "../../logic/format";
import { canCall } from "../../logic/chat-rules";
import { useDex } from "../../store/context";
import type { DexContactNotifications, DexRevealGate } from "../../wire/models";
import { Icon } from "../icons.gen";
import Dialog from "../kit/Dialog";
import "./ChatInfo.css";

export default function ChatInfo() {
  const runtime = useDex();
  const [search, setSearch] = createSignal("");
  const [confirmClear, setConfirmClear] = createSignal(false);
  const [now, setNow] = createSignal(Date.now());
  const timer = window.setInterval(() => setNow(Date.now()), 30_000);
  onCleanup(() => window.clearInterval(timer));
  const contact = createMemo(() => runtime.state.contacts.find((item) => item.ip === runtime.state.openPeer) ?? runtime.state.view?.contact ?? null);
  const detail = createMemo(() => runtime.state.contactDetail?.contact.ip === runtime.state.openPeer ? runtime.state.contactDetail : null);
  const privacy = createMemo(() => detail()?.privacy ?? contact()?.privacy ?? {});
  const notifications = createMemo(() => detail()?.notifications ?? contact()?.notifications ?? {});

  function setPrivacyChoice(field: "sendReadReceipts" | "sendTypingIndicators", value: string): void {
    const next = { ...privacy() };
    delete next[field];
    if (value !== "inherit") next[field] = value === "on";
    const peer = runtime.state.openPeer;
    if (peer) runtime.send({ t: "contact_privacy", peer, privacy: next });
  }
  function setRevealChoice(value: string): void {
    const next = { ...privacy() };
    delete next.revealGate;
    if (value !== "inherit") next.revealGate = value as DexRevealGate;
    const peer = runtime.state.openPeer;
    if (peer) runtime.send({ t: "contact_privacy", peer, privacy: next });
  }
  function setNotifications(patch: DexContactNotifications): void {
    const peer = runtime.state.openPeer;
    if (peer) runtime.send({ t: "contact_notifications", peer, prefs: { ...notifications(), ...patch } });
  }
  function searchMessages(event: SubmitEvent): void {
    event.preventDefault();
    const peer = runtime.state.openPeer;
    if (peer && search().trim()) runtime.send({ t: "search", peer, text: search().trim() });
  }

  return <aside class="dex-chat-info" aria-label="Chat information">
    <header><button type="button" aria-label="Close chat information" onClick={() => runtime.actions.setChatInfoOpen(false)}><Icon name="back" size={20} /></button><h2>Chat info</h2></header>
    <div class="dex-chat-info-scroll">
      <section class="dex-chat-info-identity"><strong>{contact()?.label ?? runtime.state.openPeer}</strong><span>{runtime.state.openPeer}</span>
        <div><button type="button" disabled={!canCall(contact(), runtime.state.isTunnelOn, runtime.state.call.phase ?? "idle")} onClick={() => { const peer = runtime.state.openPeer; if (peer) runtime.startCall(peer, false); }}><Icon name="call" size={17} />Voice call</button>
          <button type="button" disabled={!canCall(contact(), runtime.state.isTunnelOn, runtime.state.call.phase ?? "idle")} onClick={() => { const peer = runtime.state.openPeer; if (peer) runtime.startCall(peer, true); }}><Icon name="video" size={17} />Video call</button></div>
      </section>
      <section><h3>Find in chat</h3><form onSubmit={searchMessages}><input type="search" aria-label="Search this chat" placeholder="Search messages" value={search()} onInput={(event) => setSearch(event.currentTarget.value)} /><button type="submit" aria-label="Search messages"><Icon name="search" size={18} /></button></form>
        <Show when={runtime.state.chatSearchResults}><div class="dex-chat-info-results"><For each={runtime.state.chatSearchResults ?? []} fallback={<p>No messages found.</p>}>
          {(item) => <div><small>{stamp(item.ts)}</small><span>{item.isCovered ? "Covered message" : item.body || item.att?.name}</span></div>}</For></div></Show>
      </section>
      <section><h3>Media</h3><For each={runtime.state.chatMediaPeer === runtime.state.openPeer ? runtime.state.chatMedia : []} fallback={<p>No shared media yet.</p>}>
        {(item) => <a class="dex-chat-info-media" href={attachmentUrl(item.id)} target="_blank" rel="noopener noreferrer"><Icon name={item.kind === "image" ? "image" : item.kind === "video" ? "video" : "file"} size={18} />{item.att?.name ?? "Attachment"}<small>{item.att ? bytes(item.att.size) : ""}</small></a>}</For>
      </section>
      <section><h3>Links</h3><For each={runtime.state.chatLinksPeer === runtime.state.openPeer ? runtime.state.chatLinks.filter((item) => /^https?:\/\//i.test(item.url)) : []} fallback={<p>No shared links yet.</p>}>
        {(item) => <a class="dex-chat-info-link" href={item.url} target="_blank" rel="noopener noreferrer">{item.url}</a>}</For>
      </section>
      <section><h3>Privacy</h3>
        <label><span>Reveal covered messages</span><select aria-label="Reveal covered messages in this chat" value={privacy().revealGate ?? "inherit"}
          disabled={privacy().revealGate === "code" || privacy().revealGate === "device"} onChange={(event) => setRevealChoice(event.currentTarget.value)}>
          <option value="inherit">Use Dex setting</option><option value="tap">Just tap</option><option value="ask">Ask first</option><option value="code">A code on the phone</option><option value="device">The phone lock</option></select></label>
        <label><span>Send read receipts</span><select aria-label="Read receipts in this chat" value={privacy().sendReadReceipts === undefined ? "inherit" : privacy().sendReadReceipts ? "on" : "off"} onChange={(event) => setPrivacyChoice("sendReadReceipts", event.currentTarget.value)}><option value="inherit">Use Dex setting</option><option value="on">On</option><option value="off">Off</option></select></label>
        <label><span>Send typing indicators</span><select aria-label="Typing indicators in this chat" value={privacy().sendTypingIndicators === undefined ? "inherit" : privacy().sendTypingIndicators ? "on" : "off"} onChange={(event) => setPrivacyChoice("sendTypingIndicators", event.currentTarget.value)}><option value="inherit">Use Dex setting</option><option value="on">On</option><option value="off">Off</option></select></label>
      </section>
      <section><h3>Notifications</h3>
        <label><span>Use global settings</span><input type="checkbox" checked={notifications().useGlobal ?? true} onChange={(event) => setNotifications({ useGlobal: event.currentTarget.checked })} /></label>
        <Show when={notifications().useGlobal === false}><>
          <label><span>Messages</span><input type="checkbox" checked={notifications().messages ?? true} onChange={(event) => setNotifications({ messages: event.currentTarget.checked })} /></label>
          <label><span>Preview</span><input type="checkbox" checked={notifications().preview ?? true} onChange={(event) => setNotifications({ preview: event.currentTarget.checked })} /></label>
          <label><span>Sound</span><input type="checkbox" checked={notifications().sound ?? true} onChange={(event) => setNotifications({ sound: event.currentTarget.checked })} /></label>
        </></Show>
      </section>
      <section><h3>Manage</h3>
        <label><span>Disappearing messages</span><select aria-label="Disappearing messages" value={String(contact()?.disappearSeconds ?? 0)} onChange={(event) => { const peer = runtime.state.openPeer; if (peer) runtime.send({ t: "contact_flags", peer, flags: { disappearSeconds: Number(event.currentTarget.value) } }); }}>
          <option value="0">Off</option><option value="3600">1 hour</option><option value="86400">1 day</option><option value="604800">1 week</option><option value="2592000">30 days</option></select></label>
        <label><span>Mute notifications</span><select aria-label="Mute notifications" value={(contact()?.muteUntil ?? 0) < 0 ? "forever" : (contact()?.muteUntil ?? 0) > now() ? "active" : "off"} onChange={(event) => {
          const peer = runtime.state.openPeer;
          if (!peer) return;
          const value = event.currentTarget.value;
          const muteUntil = value === "forever" ? -1 : value === "hour" ? Date.now() + 3_600_000 : value === "day" ? Date.now() + 86_400_000 : 0;
          runtime.send({ t: "contact_flags", peer, flags: { muteUntil } });
        }}><option value="off">Off</option><option value="hour">1 hour</option><option value="day">1 day</option><option value="forever">Until I turn it off</option><Show when={(contact()?.muteUntil ?? 0) > now()}><option value="active" disabled>Muted</option></Show></select></label>
        <button type="button" onClick={() => { const peer = runtime.state.openPeer; if (peer) runtime.send({ t: "contact_flags", peer, flags: { isPinned: !contact()?.isPinned } }); }}>{contact()?.isPinned ? "Unpin" : "Pin"} chat</button>
        <button type="button" onClick={() => { const peer = runtime.state.openPeer; if (peer) runtime.send({ t: "contact_flags", peer, flags: { isArchived: !contact()?.isArchived } }); }}>{contact()?.isArchived ? "Unarchive" : "Archive"} chat</button>
        <button type="button" onClick={() => { const peer = runtime.state.openPeer; if (peer) runtime.send({ t: "contact_flags", peer, flags: { isBlocked: !contact()?.isBlocked } }); }}>{contact()?.isBlocked ? "Unblock" : "Block"} contact</button>
        <button type="button" class="danger" onClick={() => setConfirmClear(true)}>Clear chat history</button>
      </section>
    </div>
    <Show when={confirmClear()}><Dialog title="Clear chat history?" onClose={() => setConfirmClear(false)} actions={<><button type="button" onClick={() => setConfirmClear(false)}>Cancel</button><button type="button" data-primary="true" onClick={() => { const peer = runtime.state.openPeer; if (peer) runtime.send({ t: "clear_history", peer }); setConfirmClear(false); }}>Clear history</button></>}><p>All messages and attachments in this chat will be deleted from the phone. This cannot be undone.</p></Dialog></Show>
  </aside>;
}

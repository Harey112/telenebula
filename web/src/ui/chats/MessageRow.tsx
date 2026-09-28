import { createMemo, createSignal, For, Show } from "solid-js";
import { attachmentUrl } from "../../net/api";
import { canCopy, canDeleteForEveryone, canEdit, canReply, composerBlock } from "../../logic/chat-rules";
import { reactionCounts } from "../../logic/timeline";
import { bytes, clock, duration, remaining } from "../../logic/format";
import { useDex } from "../../store/context";
import type { DexMessage } from "../../wire/models";
import { Icon } from "../icons.gen";
import Popover from "../kit/Popover";
import EmojiPicker from "../kit/EmojiPicker";
import Dialog from "../kit/Dialog";

export interface MessageRowProps { message: DexMessage; onReveal: (id: string) => void; onJump: (id: string) => void; isHighlighted: boolean }

export default function MessageRow(props: MessageRowProps) {
  const runtime = useDex();
  const [menu, setMenu] = createSignal<{ kind: "more" | "react"; anchor: HTMLElement } | null>(null);
  const [isEmojiOpen, setEmojiOpen] = createSignal(false);
  const [isPhotoOpen, setPhotoOpen] = createSignal(false);
  const block = createMemo(() => composerBlock(runtime.state.contacts.find((item) => item.ip === props.message.peer)
    ?? runtime.state.view?.contact ?? null, runtime.state.isTunnelOn));
  const isHidden = () => props.message.isCovered && !runtime.state.revealedMessages.includes(props.message.id);
  const isMine = () => props.message.dir === "out";
  const counts = createMemo(() => reactionCounts(props.message.reactions));

  function choose(kind: "more" | "react", event: MouseEvent): void {
    const anchor = event.currentTarget;
    if (anchor instanceof HTMLElement) setMenu((current) => current?.kind === kind ? null : { kind, anchor });
  }
  function sendAction(t: "retry_action" | "cancel_action", actionId: string | undefined): void {
    if (actionId) runtime.send({ t, actionId });
  }
  function react(emoji: string): void {
    runtime.send({ t: "react", messageId: props.message.id, emoji });
    setMenu(null);
    setEmojiOpen(false);
  }
  async function copy(): Promise<void> {
    try { await navigator.clipboard.writeText(props.message.body); }
    catch { runtime.actions.addToast("error", "Could not copy this message."); }
    setMenu(null);
  }
  function reply(): void { runtime.actions.setComposerMode({ kind: "reply", message: props.message }); setMenu(null); }
  function edit(): void { runtime.actions.setComposerMode({ kind: "edit", message: props.message }); setMenu(null); }
  function remove(forEveryone: boolean): void {
    runtime.send({ t: "delete", messageId: props.message.id, forEveryone });
    setMenu(null);
  }
  function attachment() {
    const att = props.message.att;
    if (!att) return null;
    const url = attachmentUrl(props.message.id);
    if (props.message.status === "offered" && !isMine()) {
      const canAccept = !runtime.state.view?.freeBytes || att.size < runtime.state.view.freeBytes;
      return <div class="dex-attachment"><div class="dex-attachment-name"><Icon name="file" size={20} />{att.name} · {bytes(att.size)}</div>
        <div class="dex-attachment-actions"><button type="button" disabled={!canAccept} onClick={() => runtime.send({ t: "accept_offer", messageId: props.message.id })}>Accept</button>
          <button type="button" onClick={() => runtime.send({ t: "decline_offer", messageId: props.message.id })}>Decline</button></div>
        <Show when={!canAccept}><small>Not enough space on the phone</small></Show></div>;
    }
    if (props.message.status === "receiving") return <div class="dex-attachment"><span>{att.name} · {props.message.transferPct ?? 0}%</span>
      <progress max="100" value={props.message.transferPct ?? 0} />
      <button type="button" onClick={() => runtime.send({ t: "cancel_transfer", messageId: props.message.id })}>Cancel</button></div>;
    if (props.message.status === "declined" || props.message.status === "cancelled") return <p class="dex-attachment-unavailable">{props.message.status === "declined" ? "Declined" : "Cancelled"} · {att.name}</p>;
    if (!att.hasFile) return <p class="dex-attachment-unavailable">{att.name} · {isMine() && props.message.send ? "Sending…" : "Not available"}</p>;
    if (props.message.kind === "image") return <button type="button" class="dex-attachment-open" aria-label={`Open ${att.name}`} onClick={() => setPhotoOpen(true)}><img class="dex-attachment-image" src={url} alt={att.name} loading="lazy" /></button>;
    if (props.message.kind === "video") return <video class="dex-attachment-video" src={url} controls preload="metadata" playsinline aria-label={att.name} />;
    if (props.message.kind === "voice" || att.mime.startsWith("audio/")) return <div class="dex-attachment-audio"><audio src={url} controls preload="metadata" aria-label={att.name} /><span>{duration(att.durationMs ?? 0)}</span></div>;
    return <a class="dex-attachment-file" href={url} download={att.name}><Icon name="file" size={20} /><span>{att.name}<small>{bytes(att.size)}</small></span><Icon name="download" size={18} /></a>;
  }

  return <div class="dex-message-row" classList={{ outgoing: isMine(), highlighted: props.isHighlighted }} data-id={props.message.id}>
    <article class="dex-message" classList={{ outgoing: isMine() }} onContextMenu={(event) => {
      event.preventDefault();
      const anchor = event.currentTarget;
      if (anchor instanceof HTMLElement) setMenu({ kind: "more", anchor });
    }}>
      <Show when={props.message.replyTo && !props.message.isDeleted}>
        <button type="button" class="dex-message-quote" aria-label="Jump to replied message" onClick={() => { const id = props.message.replyTo?.id; if (id) props.onJump(id); }}>
          <strong>{props.message.replyTo?.name}</strong><span>{props.message.replyTo?.snippet}</span></button>
      </Show>
      <Show when={isHidden()} fallback={<Show when={props.message.isDeleted} fallback={<>{attachment()}<Show when={props.message.body && (props.message.kind === "text" || props.message.kind !== "voice")}><p>{props.message.body}</p></Show></>}><p class="dex-message-deleted">Message deleted</p></Show>}>
        <button type="button" class="dex-message-cover" aria-label="Covered message. Reveal it" onClick={() => props.onReveal(props.message.id)}><Icon name="lock" size={18} />Covered message</button>
      </Show>
      <div class="dex-message-meta">
        <Show when={props.message.expiresAt}><span><Icon name="hourglass" size={12} />{remaining(props.message.expiresAt ?? 0)}</span></Show>
        <Show when={props.message.isEdited && !props.message.isDeleted}><span>edited</span></Show>
        <time dateTime={new Date(props.message.ts).toISOString()}>{clock(props.message.ts)}</time>
        <Show when={isMine()}><span aria-label={props.message.seenAt ? "Seen" : props.message.status === "delivered" ? "Delivered" : props.message.send ? "Sending" : "Sent"}>
          <Icon name={props.message.send || props.message.status === "pending" ? "clock" : props.message.seenAt || props.message.status === "delivered" || props.message.status === "received" ? "check_check" : "check"} size={13} />
        </span></Show>
      </div>
      <Show when={!props.message.isDeleted && counts().length > 0}><div class="dex-message-reactions"><For each={counts()}>{(item) =>
        <button type="button" aria-label={`React ${item.emoji}`} aria-pressed={props.message.reactions?.[runtime.state.me?.ip ?? ""] === item.emoji}
          onClick={() => react(item.emoji)}>{item.emoji}{item.count > 1 ? ` ${item.count}` : ""}</button>}</For></div></Show>
    </article>
    <Show when={isMine() && props.message.send === "failed"}><div class="dex-message-send-state">Not sent
      <button type="button" onClick={() => sendAction("retry_action", props.message.actionId)}>Retry</button>
      <button type="button" onClick={() => sendAction("cancel_action", props.message.actionId)}>Cancel</button></div></Show>
    <Show when={isMine() && props.message.send && props.message.send !== "failed"}><span class="dex-message-send-state">{props.message.send === "waiting" ? "Waiting for them" : props.message.send === "queued" ? "Queued" : "Sending…"}</span></Show>
    <Show when={!props.message.isDeleted && !isHidden()}><div class="dex-message-tools">
      <button type="button" aria-label="React" onClick={(event) => choose("react", event)}><Icon name="emoji" size={17} /></button>
      <Show when={canReply(props.message, block())}><button type="button" aria-label="Reply" onClick={reply}><Icon name="reply" size={17} /></button></Show>
      <button type="button" aria-label="More message actions" onClick={(event) => choose("more", event)}><Icon name="menu" size={17} /></button>
    </div></Show>
    <Show when={menu()}>{(active) => <Popover anchor={active().anchor} label={active().kind === "react" ? "Reactions" : "Message actions"} onClose={() => setMenu(null)}>
      <Show when={active().kind === "react"} fallback={<>
        <Show when={canReply(props.message, block())}><button type="button" role="menuitem" onClick={reply}><Icon name="reply" size={16} />Reply</button></Show>
        <Show when={canCopy(props.message)}><button type="button" role="menuitem" onClick={() => void copy()}><Icon name="copy" size={16} />Copy</button></Show>
        <Show when={canEdit(props.message, block())}><button type="button" role="menuitem" onClick={edit}><Icon name="pencil" size={16} />Edit</button></Show>
        <Show when={props.message.att?.hasFile}><a role="menuitem" href={attachmentUrl(props.message.id)} download={props.message.att?.name} onClick={() => setMenu(null)}><Icon name="download" size={16} />Download</a></Show>
        <button type="button" role="menuitem" class="danger" onClick={() => remove(false)}><Icon name="trash" size={16} />Delete for me</button>
        <Show when={canDeleteForEveryone(props.message)}><button type="button" role="menuitem" class="danger" onClick={() => remove(true)}><Icon name="trash" size={16} />Delete for everyone</button></Show>
      </>}><><For each={runtime.state.settings?.quickReactions ?? ["👍", "❤️", "😂", "😮", "😢", "🙏"]}>{(emoji) =>
        <button type="button" role="menuitem" onClick={() => react(emoji)}>{emoji}</button>}</For>
        <button type="button" role="menuitem" onClick={() => { setMenu(null); setEmojiOpen(true); }}>More emoji</button></></Show>
    </Popover>}</Show>
    <Show when={isEmojiOpen()}><EmojiPicker onChoose={react} onClose={() => setEmojiOpen(false)} /></Show>
    <Show when={isPhotoOpen()}><Dialog title={props.message.att?.name ?? "Photo"} onClose={() => setPhotoOpen(false)} actions={<>
      <a href={attachmentUrl(props.message.id)} download={props.message.att?.name}>Download</a>
      <button type="button" onClick={() => setPhotoOpen(false)}>Close</button>
    </>}><img class="dex-media-viewer" src={attachmentUrl(props.message.id)} alt={props.message.att?.name ?? "Photo"} /></Dialog></Show>
  </div>;
}

import { createEffect, createMemo, createSignal, For, onCleanup, Show } from "solid-js";
import { composerBlock } from "../../logic/chat-rules";
import { effectiveProfile } from "../../logic/effective";
import { duration } from "../../logic/format";
import { useDex } from "../../store/context";
import { Icon } from "../icons.gen";
import EmojiPicker from "../kit/EmojiPicker";

export default function Composer() {
  const runtime = useDex();
  let picker: HTMLInputElement | undefined;
  let textarea: HTMLTextAreaElement | undefined;
  const [isEmojiOpen, setEmojiOpen] = createSignal(false);
  const contact = createMemo(() => runtime.state.contacts.find((item) => item.ip === runtime.state.openPeer)
    ?? runtime.state.view?.contact ?? null);
  const block = createMemo(() => composerBlock(contact(), runtime.state.isTunnelOn));
  const isEnterToSend = createMemo(() => effectiveProfile(runtime.state.settings).isEnterToSend);
  const [recordedFor, setRecordedFor] = createSignal(0);
  const readyRecording = () => runtime.state.recording?.kind === "ready" ? runtime.state.recording : null;
  createEffect(() => {
    const recording = runtime.state.recording;
    if (recording?.kind !== "live") return;
    const timer = window.setInterval(() => setRecordedFor(Date.now() - recording.startedAt), 1_000);
    onCleanup(() => window.clearInterval(timer));
  });

  function onKeyDown(event: KeyboardEvent): void {
    if (event.key !== "Enter" || event.isComposing) return;
    const shouldSend = isEnterToSend() ? !event.shiftKey : event.shiftKey || event.ctrlKey || event.metaKey;
    if (!shouldSend) return;
    event.preventDefault();
    runtime.sendText();
  }

  function unblock(): void {
    const peer = runtime.state.openPeer;
    if (!peer) return;
    switch (block()) {
      case "archived": runtime.send({ t: "contact_flags", peer, flags: { isArchived: false } }); break;
      case "blocked": runtime.send({ t: "contact_flags", peer, flags: { isBlocked: false } }); break;
      case "tunnel-off": runtime.send({ t: "set_tunnel", isOn: true }); break;
      case "none": break;
    }
  }

  function insertEmoji(emoji: string): void {
    const current = runtime.state.composerDraft;
    const start = textarea?.selectionStart ?? current.length;
    const end = textarea?.selectionEnd ?? start;
    runtime.actions.setComposerDraft(current.slice(0, start) + emoji + current.slice(end));
    setEmojiOpen(false);
    requestAnimationFrame(() => { textarea?.focus(); textarea?.setSelectionRange(start + emoji.length, start + emoji.length); });
  }

  return <div class="dex-composer">
    <Show when={runtime.state.uploads.length > 0}><div class="dex-composer-uploads" classList={{ "with-mode": runtime.state.composerMode !== null }}>
      <For each={runtime.state.uploads}>{(item) => <div class="dex-composer-upload">
        <span>{item.name} · {item.error ?? `${item.percent}%`}</span>
        <Show when={!item.error}><progress max="100" value={item.percent} /></Show>
        <button type="button" aria-label={`${item.error ? "Dismiss" : "Cancel"} ${item.name}`} onClick={() => item.error ? runtime.actions.dismissUpload(item.id) : runtime.cancelUpload(item.id)}><Icon name="close" size={16} /></button>
      </div>}</For>
    </div></Show>
    <Show when={runtime.state.recording}>{(recording) => <div class="dex-composer-recording">
      <Show when={recording().kind === "live"} fallback={<>
        <audio src={readyRecording()?.url} controls preload="metadata" aria-label="Voice message preview" />
        <span>{duration(readyRecording()?.durationMs ?? 0)}</span>
        <button type="button" aria-label="Send voice message" disabled={runtime.state.uploads.some((item) => item.isVoice && !item.error)} onClick={() => void runtime.sendRecording()}><Icon name="send" size={18} /></button>
      </>}><span class="dex-recording-dot" />Recording {duration(recordedFor())}
        <button type="button" aria-label="Stop recording" onClick={() => void runtime.stopRecording()}><Icon name="stop" size={18} /></button>
      </Show>
      <button type="button" aria-label="Discard recording" onClick={() => runtime.discardRecording()}><Icon name="trash" size={18} /></button>
    </div>}</Show>
    <Show when={runtime.state.composerMode}>{(mode) => <div class="dex-composer-mode">
      <div><strong>{mode().kind === "edit" ? "Edit message" : `Reply to ${mode().message.dir === "out" ? "you" : runtime.state.view?.contact.label ?? "contact"}`}</strong>
        <span>{mode().message.isCovered ? "Covered message" : mode().message.body || mode().message.att?.name || "Attachment"}</span></div>
      <button type="button" aria-label="Cancel reply or edit" onClick={() => runtime.actions.setComposerMode(null)}><Icon name="close" size={18} /></button>
    </div>}</Show>
    <Show when={block() === "none"} fallback={
      <div class="dex-composer-block" role="status">
        <span>{block() === "archived" ? "This chat is archived." : block() === "blocked" ? "You blocked this contact." : "Nebula tunnel is off."}</span>
        <button type="button" onClick={unblock}>{block() === "archived" ? "Unarchive" : block() === "blocked" ? "Unblock" : "Turn it on"}</button>
      </div>
    }>
      <input ref={picker} class="dex-visually-hidden" type="file" multiple aria-label="Choose files" onChange={(event) => {
        const files = event.currentTarget.files;
        if (files) void runtime.attach(files);
        event.currentTarget.value = "";
      }} />
      <button type="button" class="dex-composer-cover" aria-label="Attach files" onClick={() => picker?.click()}><Icon name="attach" size={20} /></button>
      <button type="button" class="dex-composer-cover" aria-label="Record voice message" disabled={runtime.state.recording !== null} onClick={() => void runtime.startRecording()}><Icon name="mic" size={20} /></button>
      <button type="button" class="dex-composer-cover" aria-label="Choose emoji" onClick={() => setEmojiOpen(true)}><Icon name="emoji" size={20} /></button>
      <button type="button" class="dex-composer-cover" aria-label="Cover message"
        aria-pressed={runtime.state.isComposerCovered}
        disabled={runtime.state.pendingText !== null || runtime.state.pendingEdit !== null}
        onClick={() => runtime.actions.toggleComposerCover()}><Icon name="lock" size={20} /></button>
      <label class="dex-composer-input">
        <span class="dex-visually-hidden">Message</span>
        <textarea ref={textarea} aria-label="Message" rows={1} placeholder={runtime.state.composerMode?.kind === "edit" ? "Edit message" : runtime.state.isComposerCovered ? "Covered message" : "Message"}
          value={runtime.state.composerDraft}
          onInput={(event) => { runtime.actions.setComposerDraft(event.currentTarget.value); runtime.typing(event.currentTarget.value); }}
          onBlur={() => runtime.typing("")}
          onKeyDown={onKeyDown} />
      </label>
      <button type="button" class="dex-composer-send" aria-label="Send"
        disabled={!runtime.state.composerDraft.trim() || runtime.state.pendingText !== null || runtime.state.pendingEdit !== null}
        onClick={() => runtime.sendText()}><Icon name="send" size={20} /></button>
      <Show when={runtime.state.pendingText}><span class="dex-composer-pending" role="status">Sending…</span></Show>
      <Show when={runtime.state.pendingEdit}><span class="dex-composer-pending" role="status">Saving…</span></Show>
    </Show>
    <Show when={isEmojiOpen()}><EmojiPicker onChoose={insertEmoji} onClose={() => setEmojiOpen(false)} /></Show>
  </div>;
}

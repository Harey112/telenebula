import { createMemo, createResource, createSignal, ErrorBoundary, For, Index, Show, Suspense } from "solid-js";
import { emojiCatalog } from "../../net/api";
import Dialog from "./Dialog";
import "./EmojiPicker.css";

export interface EmojiPickerProps { onChoose: (emoji: string) => void; onClose: () => void }

export default function EmojiPicker(props: EmojiPickerProps) {
  const [groups] = createResource(emojiCatalog);
  const [active, setActive] = createSignal(0);
  const [search, setSearch] = createSignal("");
  const shown = createMemo(() => {
    const all = groups() ?? [];
    const query = search().trim().toLocaleLowerCase();
    if (query) return all.flatMap((group) => group.emojis).filter((emoji) => emoji.includes(query));
    return all[active()]?.emojis ?? [];
  });

  return <Dialog title="Choose an emoji" onClose={props.onClose} actions={<button type="button" onClick={props.onClose}>Close</button>}>
    <div class="dex-emoji-picker"><label class="dex-visually-hidden" for="dex-emoji-search">Search emoji</label>
      <input id="dex-emoji-search" type="search" placeholder="Search emoji" value={search()} onInput={(event) => setSearch(event.currentTarget.value)} />
      <ErrorBoundary fallback={<p role="alert">The emoji catalogue could not be loaded.</p>}><Suspense fallback={<p>Loading emoji…</p>}>
        <div role="tablist" aria-label="Emoji groups"><For each={groups() ?? []}>{(group, index) => <button type="button" role="tab" aria-selected={active() === index()}
          onClick={() => setActive(index())}>{group.title}</button>}</For></div>
        <div class="dex-emoji-grid"><Index each={shown()}>{(emoji) => <button type="button" aria-label={`Choose ${emoji()}`} onClick={() => props.onChoose(emoji())}>{emoji()}</button>}</Index></div>
        <Show when={(groups()?.length ?? 0) === 0}><p>No emoji available.</p></Show>
      </Suspense></ErrorBoundary>
    </div>
  </Dialog>;
}

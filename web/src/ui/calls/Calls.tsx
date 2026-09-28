import { createEffect, createMemo, createSignal, For, Show } from "solid-js";
import { canCall } from "../../logic/chat-rules";
import { duration, stamp } from "../../logic/format";
import { useDex } from "../../store/context";
import type { DexCallLog } from "../../wire/models";
import { Icon } from "../icons.gen";
import Dialog from "../kit/Dialog";
import "./Calls.css";

type Filter = "all" | "missed" | "incoming" | "outgoing";

function outcome(log: DexCallLog): string {
  switch (log.outcome) {
    case "answered": return log.connectedAt ? duration(log.endedAt - log.connectedAt) : "Answered";
    case "missed": return "Missed";
    case "declined": return "Declined";
    case "no-answer": return "No answer";
    case "unreachable": return "Unreachable";
    case "cancelled": return "Cancelled";
    case "failed": return "Failed";
  }
}

export default function Calls() {
  const runtime = useDex();
  const [query, setQuery] = createSignal("");
  const [filter, setFilter] = createSignal<Filter>("all");
  const [sortOldest, setSortOldest] = createSignal(false);
  const [selected, setSelected] = createSignal<string[]>([]);
  const [isConfirmingDelete, setConfirmingDelete] = createSignal(false);
  createEffect(() => {
    const ids = new Set(runtime.state.callLogs.map((log) => log.id));
    setSelected((current) => {
      const remaining = current.filter((id) => ids.has(id));
      return remaining.length === current.length ? current : remaining;
    });
  });
  const rows = createMemo(() => {
    const oldest = sortOldest();
    return runtime.state.callLogs.filter((log) => {
    const match = filter() === "all" || (filter() === "missed" ? log.outcome === "missed" : filter() === "incoming" ? log.dir === "in" : log.dir === "out");
    return match && `${log.label} ${log.peer}`.toLocaleLowerCase().includes(query().toLocaleLowerCase());
    }).sort((left, right) => oldest ? left.startedAt - right.startedAt : right.startedAt - left.startedAt);
  });

  function toggle(id: string): void { setSelected((items) => items.includes(id) ? items.filter((item) => item !== id) : [...items, id]); }
  function openChat(peer: string): void { runtime.setTab("chats"); runtime.openChat(peer); }
  function deleteSelected(): void {
    runtime.send({ t: "delete_call_logs", ids: selected() });
    setConfirmingDelete(false);
  }

  return <section class="dex-calls" aria-label="Call history">
    <header><h1>Calls</h1><label><Icon name="search" size={18} /><input type="search" aria-label="Search calls" placeholder="Search calls" value={query()} onInput={(event) => setQuery(event.currentTarget.value)} /></label></header>
    <div class="dex-calls-tools"><div role="tablist" aria-label="Filter calls">
      <For each={["all", "missed", "incoming", "outgoing"] as Filter[]}>{(item) => <button type="button" role="tab" aria-selected={filter() === item} onClick={() => setFilter(item)}>{item[0]?.toUpperCase()}{item.slice(1)}</button>}</For>
    </div><label>Sort calls <select aria-label="Sort calls" value={sortOldest() ? "oldest" : "newest"} onChange={(event) => setSortOldest(event.currentTarget.value === "oldest")}><option value="newest">Newest first</option><option value="oldest">Oldest first</option></select></label></div>
    <Show when={selected().length > 0}><div class="dex-calls-selection"><span>{selected().length} call{selected().length === 1 ? "" : "s"} selected</span><button type="button" onClick={() => setConfirmingDelete(true)}>Delete</button><button type="button" onClick={() => setSelected([])}>Cancel</button></div></Show>
    <div class="dex-calls-list"><For each={rows()} fallback={<p class="dex-calls-empty">{runtime.state.callLogs.length ? "No calls match." : "No calls yet."}</p>}>
      {(log) => <article class="dex-call-row">
        <input type="checkbox" aria-label={`Select the call with ${log.label}, ${stamp(log.startedAt)}`} checked={selected().includes(log.id)} onChange={() => toggle(log.id)} />
        <Icon name={log.outcome === "missed" ? "call_missed" : log.dir === "in" ? "call_in" : "call_out"} size={20} />
        <div class="dex-call-summary"><strong>{log.label}</strong><span>{log.isVideo ? "Video" : "Voice"} · {stamp(log.startedAt)} · {outcome(log)}</span></div>
        <div class="dex-call-actions">
          <button type="button" aria-label={`Voice call ${log.label}`} disabled={!canCall(runtime.state.contacts.find((item) => item.ip === log.peer) ?? null, runtime.state.isTunnelOn, runtime.state.call.phase ?? "idle")} onClick={() => runtime.startCall(log.peer, false)}><Icon name="call" size={18} /></button>
          <button type="button" aria-label={`Video call ${log.label}`} disabled={!canCall(runtime.state.contacts.find((item) => item.ip === log.peer) ?? null, runtime.state.isTunnelOn, runtime.state.call.phase ?? "idle")} onClick={() => runtime.startCall(log.peer, true)}><Icon name="video" size={18} /></button>
          <button type="button" aria-label={`Open the chat with ${log.label}`} onClick={() => openChat(log.peer)}><Icon name="chats" size={18} /></button>
        </div>
      </article>}
    </For></div>
    <Show when={isConfirmingDelete()}><Dialog title="Delete call history?" onClose={() => setConfirmingDelete(false)} actions={<><button type="button" onClick={() => setConfirmingDelete(false)}>Cancel</button><button type="button" data-primary="true" onClick={deleteSelected}>Delete selected</button></>}><p>Delete {selected().length} selected call{selected().length === 1 ? "" : "s"} from the phone?</p></Dialog></Show>
  </section>;
}

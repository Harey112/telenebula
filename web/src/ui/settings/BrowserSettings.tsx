import { createEffect, createMemo, createSignal, For, Index, Match, onCleanup, Show, Switch } from "solid-js";
import { bytes, duration, stamp } from "../../logic/format";
import { presenceState, shownProfile } from "../../logic/effective";
import { useDex } from "../../store/context";
import type { SettingsTab } from "../../store";
import type { DexDensity, DexRevealGate, DexTextSize, DexThemeMode } from "../../wire/models";
import Dialog from "../kit/Dialog";
import EmojiPicker from "../kit/EmojiPicker";
import "./BrowserSettings.css";

const categories: { id: SettingsTab; label: string }[] = [
  { id: "account", label: "Account" }, { id: "status", label: "Status" },
  { id: "appearance", label: "Appearance" }, { id: "chats", label: "Chats" },
  { id: "notifications", label: "Notifications" }, { id: "privacy", label: "Privacy" },
  { id: "network", label: "Network" }, { id: "storage", label: "Storage" },
  { id: "diagnostics", label: "Diagnostics" },
];

interface InfoProps { label: string; value: string }
function Info(props: InfoProps) { return <div class="dex-settings-row dex-settings-info"><strong>{props.label}</strong><span>{props.value || "—"}</span></div>; }

export default function BrowserSettings() {
  const runtime = useDex();
  const profile = createMemo(() => shownProfile(runtime.state.profileEdits, runtime.state.settings?.dexProfile ?? {}));
  const [confirm, setConfirm] = createSignal<"history" | "orphans" | null>(null);
  const [reactionSlot, setReactionSlot] = createSignal<number | null>(null);
  const [permission, setPermission] = createSignal(typeof Notification === "undefined" ? "unsupported" : Notification.permission);
  const [now, setNow] = createSignal(Date.now());
  createEffect(() => {
    if (runtime.state.settingsTab !== "status") return;
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    onCleanup(() => window.clearInterval(timer));
  });

  function requestNotifications(): void {
    if (typeof Notification === "undefined") return;
    void Notification.requestPermission().then(setPermission).catch(() => runtime.actions.addToast("warning", "The browser did not grant notification permission."));
  }
  function confirmed(): void {
    if (confirm() === "history") runtime.send({ t: "clear_all_history" });
    if (confirm() === "orphans") runtime.send({ t: "clear_orphans" });
    setConfirm(null);
  }

  return <section class="dex-settings-pane dex-settings-layout" aria-label="Dex browser settings">
    <nav class="dex-settings-categories" aria-label="Settings categories"><h1>Settings</h1><div role="tablist" aria-label="Settings">
      <For each={categories}>{(item) => <button type="button" role="tab" aria-selected={runtime.state.settingsTab === item.id}
        onClick={() => runtime.setSettingsTab(item.id)}>{item.label}</button>}</For>
    </div></nav>
    <div class="dex-settings-content"><header><h1>{categories.find((item) => item.id === runtime.state.settingsTab)?.label}</h1></header>
      <Show when={runtime.state.settings} fallback={<p class="dex-settings-waiting">Waiting for the phone…</p>}>
        <div class="dex-settings-sections"><Switch>
          <Match when={runtime.state.settingsTab === "account"}>
            <section class="dex-settings-section"><h2>Identity</h2>
              <Info label="Node ID" value={runtime.state.account?.certName ? `@${runtime.state.account.certName}` : ""} />
              <Info label="Nebula IPv6" value={runtime.state.account?.overlayIp ?? ""} />
              <Info label="Certificate status" value={runtime.state.account?.certStatus ?? ""} />
              <Info label="Valid until" value={runtime.state.account?.certNotAfter ?? ""} />
              <Info label="Fingerprint" value={runtime.state.account?.certFingerprint ?? ""} />
            </section>
            <section class="dex-settings-section"><h2>Phone app</h2>
              <Info label="Version" value={runtime.state.account?.appVersion ?? ""} />
              <Info label="Core version" value={runtime.state.account?.coreVersion ?? ""} />
              <Info label="Updates" value={runtime.state.updates?.isUpdateAvailable ? `Version ${runtime.state.updates.latestVersion ?? ""} is available on the phone` : runtime.state.updates?.lastCheckedAt ? `Checked ${stamp(runtime.state.updates.lastCheckedAt)}` : "Not checked yet"} />
              <button type="button" class="dex-settings-action" onClick={() => runtime.send({ t: "check_updates" })}>Check for an update</button>
            </section>
          </Match>
          <Match when={runtime.state.settingsTab === "status"}>
            <section class="dex-settings-section"><h2>Presence</h2>
              <label class="dex-settings-row"><span><strong>Share when I am online</strong></span><input type="checkbox"
                checked={runtime.state.settings?.presence?.isShared ?? true}
                onChange={(event) => runtime.send({ t: "set_settings", patch: { presence: { isShared: event.currentTarget.checked } } })} /></label>
              <label class="dex-settings-row"><span><strong>Pause sharing</strong></span><select aria-label="Pause sharing" value={String(presenceState(runtime.state.settings?.presence ?? {}, now()).isPaused ? runtime.state.settings?.presence?.pauseMinutes ?? 0 : 0)}
                onChange={(event) => { const minutes = Number(event.currentTarget.value); runtime.send({ t: "set_settings", patch: { presence: { isShared: true, pauseMinutes: minutes, pausedUntil: minutes ? Date.now() + minutes * 60_000 : 0 } } }); }}>
                <option value="0">Off</option><option value="30">30 minutes</option><option value="60">1 hour</option><option value="480">8 hours</option><option value="1440">24 hours</option></select></label>
              <Info label="Contacts see" value={runtime.state.isTunnelOn === false ? "Offline — tunnel off" : presenceState(runtime.state.settings?.presence ?? {}, now()).isActive ? "Online while the phone is active" : "Reachable"} />
            </section>
          </Match>
          <Match when={runtime.state.settingsTab === "appearance"}>
            <section class="dex-settings-section"><h2>Appearance in Dex</h2>
              <label class="dex-settings-row"><span><strong>Theme</strong><small>Shared across your Dex browsers.</small></span><select aria-label="Theme" value={profile().themeMode}
                onChange={(event) => runtime.editProfile((current) => ({ ...current, themeMode: event.currentTarget.value as DexThemeMode }))}>
                <option value="system">Follow the system</option><option value="light">Light</option><option value="dark">Dark</option></select></label>
              <label class="dex-settings-row"><span><strong>Colour theme</strong></span><select aria-label="Colour theme" value={profile().colorTheme}
                onChange={(event) => runtime.editProfile((current) => ({ ...current, colorTheme: event.currentTarget.value }))}>
                <For each={["sky", "forest", "amber", "rose", "violet", "slate", "custom"]}>{(name) => <option value={name}>{name[0]?.toUpperCase()}{name.slice(1)}</option>}</For></select></label>
              <label class="dex-settings-row"><span><strong>Custom accent</strong></span><input type="color" aria-label="Custom accent" disabled={profile().colorTheme !== "custom"}
                value={profile().customAccent} onChange={(event) => runtime.editProfile((current) => ({ ...current, customAccent: event.currentTarget.value }))} /></label>
            </section>
          </Match>
          <Match when={runtime.state.settingsTab === "chats"}>
            <section class="dex-settings-section"><h2>Reading and composing in Dex</h2>
              <label class="dex-settings-row"><span><strong>Text size</strong></span><select aria-label="Text size" value={profile().chatTextSize}
                onChange={(event) => runtime.editProfile((current) => ({ ...current, chatTextSize: event.currentTarget.value as DexTextSize }))}>
                <option value="small">Small</option><option value="medium">Medium</option><option value="large">Large</option></select></label>
              <label class="dex-settings-row"><span><strong>Message density</strong></span><select aria-label="Message density" value={profile().messageDensity}
                onChange={(event) => runtime.editProfile((current) => ({ ...current, messageDensity: event.currentTarget.value as DexDensity }))}>
                <option value="comfortable">Comfortable</option><option value="compact">Compact</option></select></label>
              <label class="dex-settings-row"><span><strong>Enter sends the message</strong><small>Shift+Enter adds a new line when this is on.</small></span>
                <input type="checkbox" checked={profile().isEnterToSend} onChange={(event) => runtime.editProfile((current) => ({ ...current, isEnterToSend: event.currentTarget.checked }))} /></label>
            </section>
            <section class="dex-settings-section"><h2>Quick reactions</h2><Index each={[0, 1, 2, 3, 4, 5]}>{(slot) =>
              <div class="dex-settings-row"><span>Slot {slot() + 1}</span><button type="button" onClick={() => setReactionSlot(slot())}>{runtime.state.settings?.quickReactions?.[slot()] ?? "Change"}</button></div>
            }</Index></section>
          </Match>
          <Match when={runtime.state.settingsTab === "notifications"}>
            <section class="dex-settings-section"><h2>This browser</h2><Info label="Permission" value={permission() === "granted" ? "Allowed" : permission() === "denied" ? "Blocked in browser settings" : permission() === "unsupported" ? "Not supported" : "Not asked yet"} />
              <Show when={permission() === "default"}><button type="button" class="dex-settings-action" onClick={requestNotifications}>Allow notifications</button></Show></section>
            <section class="dex-settings-section"><h2>New messages in Dex</h2>
              <label class="dex-settings-row"><span><strong>Message notifications</strong></span><input type="checkbox" disabled={permission() !== "granted"} checked={profile().notificationsEnabled}
                onChange={(event) => runtime.editProfile((current) => ({ ...current, notificationsEnabled: event.currentTarget.checked }))} /></label>
              <label class="dex-settings-row"><span><strong>Show a preview</strong></span><input type="checkbox" disabled={permission() !== "granted" || !profile().notificationsEnabled} checked={profile().notificationPreview}
                onChange={(event) => runtime.editProfile((current) => ({ ...current, notificationPreview: event.currentTarget.checked }))} /></label>
              <label class="dex-settings-row"><span><strong>Sound</strong></span><input type="checkbox" disabled={permission() !== "granted" || !profile().notificationsEnabled} checked={profile().notificationSound}
                onChange={(event) => runtime.editProfile((current) => ({ ...current, notificationSound: event.currentTarget.checked }))} /></label>
              <button type="button" class="dex-settings-action" onClick={() => runtime.editProfile((current) => ({ ...current, notificationsEnabled: true, notificationPreview: true, notificationSound: true }))}>Reset notifications</button>
            </section>
          </Match>
          <Match when={runtime.state.settingsTab === "privacy"}>
            <section class="dex-settings-section"><h2>What contacts see from Dex</h2>
              <label class="dex-settings-row"><span><strong>Send read receipts</strong></span><input type="checkbox" checked={profile().sendReadReceipts}
                onChange={(event) => runtime.editProfile((current) => ({ ...current, sendReadReceipts: event.currentTarget.checked }))} /></label>
              <label class="dex-settings-row"><span><strong>Send typing indicators</strong></span><input type="checkbox" checked={profile().sendTypingIndicators}
                onChange={(event) => runtime.editProfile((current) => ({ ...current, sendTypingIndicators: event.currentTarget.checked }))} /></label>
            </section>
            <section class="dex-settings-section"><h2>Covered messages in Dex</h2>
              <label class="dex-settings-row"><span><strong>Reveal covered messages</strong><small>A chat's own setting takes precedence.</small></span>
                <select aria-label="Reveal covered messages" value={profile().coverRevealGate} onChange={(event) => runtime.editProfile((current) => ({ ...current, coverRevealGate: event.currentTarget.value as DexRevealGate }))}>
                  <option value="tap">Just tap</option><option value="ask">Ask first</option><option value="code">A code on the phone</option></select></label>
              <Show when={profile().coverRevealGate === "code"}><p class="dex-settings-note">Messages protected by a code open on the phone; their content is withheld from Dex.</p></Show>
            </section>
            <section class="dex-settings-section"><h2>Blocked contacts</h2><For each={runtime.state.contacts.filter((item) => item.isBlocked)} fallback={<p class="dex-settings-note">Nobody is blocked.</p>}>
              {(contact) => <div class="dex-settings-row"><span>{contact.label}</span><button type="button" onClick={() => runtime.send({ t: "contact_flags", peer: contact.ip, flags: { isBlocked: false } })}>Unblock</button></div>}</For></section>
          </Match>
          <Match when={runtime.state.settingsTab === "network"}>
            <section class="dex-settings-section"><h2>Tunnel</h2>
              <label class="dex-settings-row"><span><strong>Nebula tunnel</strong></span><input type="checkbox" checked={runtime.state.isTunnelOn ?? runtime.state.network?.isTunnelOn ?? false}
                onChange={(event) => runtime.send({ t: "set_tunnel", isOn: event.currentTarget.checked })} /></label>
              <Info label="Tunnel uptime" value={duration(runtime.state.network?.tunnelUptimeMs ?? 0)} />
              <Info label="Messaging uptime" value={duration(runtime.state.network?.engineUptimeMs ?? 0)} />
              <Info label="Connected peers" value={String(runtime.state.network?.connectedCount ?? 0)} />
              <Info label="Lighthouse" value={runtime.state.network?.lighthouseStatus ?? ""} />
            </section>
            <section class="dex-settings-section"><h2>Browser connection</h2>
              <Info label="Dex port" value={String(runtime.state.settings?.dexPort ?? "")} />
              <Info label="Browsers allowed at once" value={String(runtime.state.settings?.dexMaxClients ?? "")} />
              <Info label="Message link" value={`↑ ${bytes(runtime.state.network?.bytesSent ?? 0)}  ↓ ${bytes(runtime.state.network?.bytesReceived ?? 0)}`} />
            </section>
            <section class="dex-settings-section"><h2>Peers</h2><For each={runtime.state.network?.peers ?? []} fallback={<p class="dex-settings-note">No tunnels established yet.</p>}>
              {(peer) => <Info label={peer.label} value={`${peer.isConnected ? "Connected" : "Idle"} · ${peer.endpoint ?? ""}${peer.latencyMs ? ` · ${peer.latencyMs} ms` : ""}`} />}</For></section>
          </Match>
          <Match when={runtime.state.settingsTab === "storage"}>
            <section class="dex-settings-section"><h2>Stored on the phone</h2>
              <Info label="Messages" value={String(runtime.state.storage?.messages ?? 0)} />
              <Info label="Contacts" value={String(runtime.state.storage?.contacts ?? 0)} />
              <Info label="Database" value={bytes(runtime.state.storage?.dbBytes ?? 0)} />
              <Info label="Attachments" value={`${runtime.state.storage?.attachmentsCount ?? 0} files · ${bytes(runtime.state.storage?.attachmentsBytes ?? 0)}`} />
              <Info label="Partial transfers" value={`${runtime.state.storage?.partialCount ?? 0} files · ${bytes(runtime.state.storage?.partialBytes ?? 0)}`} />
              <Info label="Free space" value={bytes(runtime.state.storage?.freeBytes ?? 0)} />
            </section>
            <section class="dex-settings-section"><h2>Tidying up</h2><Info label="Unused media" value={`${runtime.state.storage?.orphanCount ?? 0} files · ${bytes(runtime.state.storage?.orphanBytes ?? 0)}`} />
              <button type="button" class="dex-settings-action" disabled={!runtime.state.storage?.orphanCount} onClick={() => setConfirm("orphans")}>Delete unused media</button></section>
            <section class="dex-settings-section"><h2>Danger</h2><button type="button" class="dex-settings-action danger" onClick={() => setConfirm("history")}>Clear all history</button></section>
          </Match>
          <Match when={runtime.state.settingsTab === "diagnostics"}>
            <section class="dex-settings-section"><h2>Outbox</h2>
              <Info label="Queued" value={String(runtime.state.diagnostics?.queuedCount ?? 0)} />
              <Info label="Peers waiting" value={String(runtime.state.diagnostics?.queuedPeers ?? 0)} />
              <Info label="Failed" value={String(runtime.state.diagnostics?.failedCount ?? 0)} />
              <button type="button" class="dex-settings-action" disabled={!runtime.state.diagnostics?.failedCount} onClick={() => runtime.send({ t: "retry_failed" })}>Retry failed messages</button>
            </section>
            <Show when={(runtime.state.diagnostics?.callTrail?.length ?? 0) > 0}><section class="dex-settings-section"><h2>Last call</h2><For each={runtime.state.diagnostics?.callTrail}>{(line) => <p class="dex-settings-log">{line}</p>}</For></section></Show>
            <section class="dex-settings-section"><h2>Nebula log</h2><pre class="dex-settings-log">{runtime.state.diagnostics?.logTail || "Nothing logged yet."}</pre></section>
          </Match>
        </Switch></div>
      </Show>
    </div>
    <Show when={confirm()}>{(action) => <Dialog title={action() === "history" ? "Clear all history?" : "Delete unused media?"} onClose={() => setConfirm(null)} actions={<>
      <button type="button" onClick={() => setConfirm(null)}>Cancel</button><button type="button" data-primary="true" onClick={confirmed}>Delete</button>
    </>}><p>{action() === "history" ? "This deletes every chat message and attachment from the phone. It cannot be undone." : "This removes media no message refers to from the phone."}</p></Dialog>}</Show>
    <Show when={reactionSlot() !== null}><EmojiPicker onChoose={(emoji) => { const slot = reactionSlot(); if (slot !== null) runtime.send({ t: "set_quick_reaction", slot, emoji }); setReactionSlot(null); }} onClose={() => setReactionSlot(null)} /></Show>
  </section>;
}

import { createMemo, For, Show } from "solid-js";
import { contactLabel, contactSecondary } from "../../logic/contact-lists";
import { canCall } from "../../logic/chat-rules";
import { bytes, lastSeen, listTime, stamp } from "../../logic/format";
import { useDex } from "../../store/context";
import type { DexContact } from "../../wire/models";
import { Icon } from "../icons.gen";

interface DetailRowProps { label: string; value: string; isMono?: boolean }

function DetailRow(props: DetailRowProps) {
  return <div class="dex-contact-info-row">
    <span>{props.label}</span><strong classList={{ "dex-contact-mono": props.isMono }}>{props.value || "—"}</strong>
  </div>;
}

export default function ContactDetail() {
  const runtime = useDex();
  const contact = createMemo<DexContact | null>(() => {
    const peer = runtime.state.selectedContact;
    if (!peer) return null;
    return runtime.state.contacts.find((item) => item.ip === peer)
      ?? (runtime.state.contactDetail?.contact.ip === peer ? runtime.state.contactDetail.contact : null);
  });
  const detail = createMemo(() => runtime.state.contactDetail?.contact.ip === runtime.state.selectedContact
    ? runtime.state.contactDetail : null);
  const presence = createMemo(() => {
    const peer = runtime.state.selectedContact;
    return detail()?.presence ?? (peer ? runtime.state.presence[peer] : undefined) ?? "offline";
  });

  return <section class="dex-contact-detail" aria-label="Contact details">
    <Show when={runtime.state.selectedContact} fallback={<p class="dex-contact-detail-empty">Choose a contact to see their details.</p>}>
      <Show when={contact()} fallback={<p class="dex-contact-detail-empty">Loading contact…</p>}>
        {(selected) => <>
          <div class="dex-contact-detail-top">
            <button type="button" class="dex-icon-button dex-contact-back" aria-label="Back to contacts"
              onClick={() => runtime.selectContact(null)}><Icon name="back" size={20} /></button>
            <button type="button" class="dex-icon-button dex-contact-edit" aria-label="Edit contact"
              onClick={() => runtime.actions.openEditContact(selected())}><Icon name="pencil" size={20} /></button>
          </div>
          <div class="dex-contact-identity">
            <span class="dex-contact-avatar" aria-hidden="true">{contactLabel(selected()).slice(0, 1).toLocaleUpperCase()}</span>
            <h2>{contactLabel(selected())}</h2>
            <Show when={contactSecondary(selected())}><p>{contactSecondary(selected())}</p></Show>
            <p>{presence() === "online" ? "Online" : presence() === "reachable" ? "Reachable" : lastSeen(selected().lastSeenAt)}</p>
            <div class="dex-contact-actions">
              <button type="button" onClick={() => { runtime.setTab("chats"); runtime.openChat(selected().ip); }}>
                <Icon name="chats" size={18} /> Message
              </button>
              <button type="button" disabled={!canCall(selected(), runtime.state.isTunnelOn, runtime.state.call.phase ?? "idle")}
                onClick={() => runtime.startCall(selected().ip, false)}><Icon name="call" size={18} /> Voice call</button>
              <button type="button" disabled={!canCall(selected(), runtime.state.isTunnelOn, runtime.state.call.phase ?? "idle")}
                onClick={() => runtime.startCall(selected().ip, true)}><Icon name="video" size={18} /> Video call</button>
              <button type="button" disabled={runtime.state.isTunnelOn === false}
                onClick={() => runtime.send({ t: "ping_peer", peer: selected().ip })}>
                <Icon name="ping" size={18} /> Ping
              </button>
            </div>
          </div>
          <div class="dex-contact-sections">
            <section class="dex-contact-section"><h3>Details</h3>
              <DetailRow label="Username" value={selected().name || "Not announced yet"} />
              <Show when={selected().nickname}><DetailRow label="Nickname" value={selected().nickname ?? ""} /></Show>
              <DetailRow label="Nebula IPv6" value={selected().ip} isMono />
              <Show when={selected().addedAt}><DetailRow label="Added" value={listTime(selected().addedAt ?? 0)} /></Show>
              <DetailRow label="Last seen" value={lastSeen(selected().lastSeenAt)} />
              <Show when={selected().notes}><DetailRow label="Notes" value={selected().notes ?? ""} /></Show>
            </section>
            <Show when={detail()}>
              {(loaded) => <>
                <section class="dex-contact-section"><h3>Connection</h3>
                  <DetailRow label="Status" value={loaded().connectionStatus || (loaded().stats?.isConnected ? "Connected" : "Not connected")} />
                  <Show when={loaded().endpoint}><DetailRow label="Endpoint" value={loaded().endpoint ?? ""} isMono /></Show>
                  <Show when={loaded().clientVersion}><DetailRow label="Their app" value={loaded().clientVersion ?? ""} /></Show>
                  <Show when={loaded().peerCertName}><DetailRow label="Certificate name" value={loaded().peerCertName ?? ""} /></Show>
                  <Show when={loaded().peerCertFingerprint}><DetailRow label="Fingerprint" value={loaded().peerCertFingerprint ?? ""} isMono /></Show>
                  <DetailRow label="Outbox" value={`${loaded().queued ?? 0} queued · ${loaded().failed ?? 0} failed`} />
                  <Show when={runtime.state.pings[selected().ip]}>
                    {(ping) => <DetailRow label="Reachability" value={ping().error ?? `Answered in ${ping().rttMs} ms`} />}
                  </Show>
                </section>
                <Show when={loaded().stats}>
                  {(stats) => <section class="dex-contact-section"><h3>Between you</h3>
                    <DetailRow label="Messages" value={`↑ ${stats().messagesSent ?? 0}   ↓ ${stats().messagesReceived ?? 0}`} />
                    <DetailRow label="Media" value={`↑ ${stats().mediaSent ?? 0}   ↓ ${stats().mediaReceived ?? 0}`} />
                    <DetailRow label="Data" value={`↑ ${bytes(stats().bytesSent ?? 0)}   ↓ ${bytes(stats().bytesReceived ?? 0)}`} />
                    <Show when={stats().firstMessageAt}><DetailRow label="First message" value={listTime(stats().firstMessageAt ?? 0)} /></Show>
                    <Show when={stats().lastActivityAt}><DetailRow label="Last activity" value={listTime(stats().lastActivityAt ?? 0)} /></Show>
                  </section>}
                </Show>
                <Show when={(loaded().calls?.length ?? 0) > 0}>
                  <section class="dex-contact-section"><h3>Recent calls</h3>
                    <For each={loaded().calls?.slice(0, 10)}>{(call) =>
                      <DetailRow label={stamp(call.startedAt)} value={`${call.isVideo ? "Video" : "Voice"} · ${call.outcome}`} />
                    }</For>
                  </section>
                </Show>
              </>}
            </Show>
            <section class="dex-contact-section"><h3>Manage</h3>
              <div class="dex-contact-manage-row"><span>Pin this contact</span>
                <button type="button" aria-pressed={Boolean(selected().isPinned)}
                  onClick={() => runtime.send({ t: "contact_flags", peer: selected().ip, flags: { isPinned: !selected().isPinned } })}>
                  {selected().isPinned ? "Unpin" : "Pin"}
                </button>
              </div>
              <div class="dex-contact-manage-row"><span>Archive this chat</span>
                <button type="button" aria-pressed={Boolean(selected().isArchived)}
                  onClick={() => runtime.send({ t: "contact_flags", peer: selected().ip, flags: { isArchived: !selected().isArchived } })}>
                  {selected().isArchived ? "Unarchive" : "Archive"}
                </button>
              </div>
              <div class="dex-contact-manage-row"><span>Block messages and calls</span>
                <button type="button" aria-pressed={Boolean(selected().isBlocked)}
                  onClick={() => runtime.send({ t: "contact_flags", peer: selected().ip, flags: { isBlocked: !selected().isBlocked } })}>
                  {selected().isBlocked ? "Unblock" : "Block"}
                </button>
              </div>
              <div class="dex-contact-manage-row"><span>Change Nebula address</span>
                <button type="button" onClick={() => runtime.actions.openChangeContactIp(selected())}>Change</button>
              </div>
              <div class="dex-contact-danger-row">
                <div><strong>Delete contact</strong><p>Remove this contact, all messages and their call history from the phone.</p></div>
                <button type="button" onClick={() => runtime.actions.openDeleteContact(selected(), contactLabel(selected()))}>Delete</button>
              </div>
            </section>
          </div>
        </>}
      </Show>
    </Show>
  </section>;
}

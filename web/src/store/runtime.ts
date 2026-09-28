import { login, logout, session, upload } from "../net/api";
import { DexSocket } from "../net/socket";
import { contactLabel } from "../logic/contact-lists";
import { shownProfile, type EffectiveProfile } from "../logic/effective";
import { composerBlock } from "../logic/chat-rules";
import { newContactProblem } from "../logic/overlay";
import { VoiceRecorder, voiceExtension, type VoiceClip } from "../media/recorder";
import { CallMedia } from "../call/media";
import type { ClientFrame } from "../wire/frames";
import { createDexStore, type SettingsTab, type Tab } from "./index";

const watchedSections: Partial<Record<SettingsTab, string[]>> = {
  account: ["account", "updates"],
  network: ["network", "account"],
  storage: ["storage"],
  diagnostics: ["diagnostics"],
};

export function createDexRuntime() {
  const store = createDexStore();
  let disposed = false;
  let reconnectChecks = 0;
  let nextTextRequestId = 1;
  let nextUploadId = 1;
  let activeUploadCount = 0;
  let voiceUploadId: number | null = null;
  let activityGeneration = 0;
  let recordingGeneration = 0;
  const uploadHandles = new Map<number, () => void>();
  const recorder = new VoiceRecorder();
  let readyClip: VoiceClip | null = null;
  let typingPeer: string | null = null;
  let lastTypingSentAt = 0;
  let typingTimer: number | null = null;
  const media = new CallMedia({
    onSdp(callId, sdp, sdpType) { send({ t: "call_sdp", callId, sdp, sdpType }); },
    onIce(callId, candidate) { socket.send({ t: "call_ice", callId, ...(candidate ? { candidate } : {}) }); },
    onConnected(callId) { send({ t: "call_connected", callId }); },
    onFailed(callId, reason) { send({ t: "call_failed", callId, reason }); store.actions.addToast("error", reason); },
    onWarning(reason) { store.actions.addToast("warning", reason); },
    onLocalStream(stream) { store.actions.setCallLocalStream(stream); },
    onRemoteStream(stream) { store.actions.setCallRemoteStream(stream); },
  });

  const socket = new DexSocket({
    onFrame(frame) {
      if (frame.t === "chats") notifyNewMessages(store.state.chats, frame.items);
      if (frame.t === "call_state" && frame.state.phase === "incoming" && store.state.call.phase !== "incoming") {
        notify(frame.state.peer?.name ?? "Call", frame.state.video ? "Incoming video call" : "Incoming call", "call", true);
      }
      const added = frame.t === "done" && frame.what === "contact_add" && store.state.dialog?.kind === "add-contact"
        && store.state.dialog.isBusy ? store.state.dialog.ip : null;
      const edited = frame.t === "done" && frame.what === "contact_save" && store.state.dialog?.kind === "edit-contact"
        && store.state.dialog.isBusy ? store.state.dialog.peer : null;
      const deleted = frame.t === "done" && frame.what === "contact_delete" && store.state.dialog?.kind === "delete-contact"
        && store.state.dialog.isBusy ? store.state.dialog.peer : null;
      const changedIp = frame.t === "done" && frame.what === "contact_change_ip" && store.state.dialog?.kind === "change-contact-ip"
        && store.state.dialog.isBusy ? store.state.dialog.newIp : null;
      const wasOpen = deleted !== null && store.state.openPeer === deleted;
      store.actions.receive(frame);
      switch (frame.t) {
        case "call_media":
          if (store.state.call.callId && store.state.call.callId !== frame.callId
            && store.state.call.phase !== "idle" && store.state.call.phase !== "ended") break;
          void media.open({ callId: frame.callId, isOfferer: frame.role === "offerer", video: frame.video,
            iceServers: frame.iceServers, ...(frame.remoteSdp ? { remoteSdp: frame.remoteSdp } : {}),
            ...(frame.remoteSdpType ? { remoteSdpType: frame.remoteSdpType } : {}),
            ...(frame.isRestart !== undefined ? { isRestart: frame.isRestart } : {}) });
          break;
        case "call_sdp": void media.remoteSdp(frame.callId, frame.sdp, frame.sdpType); break;
        case "call_ice": media.remoteIce(frame.callId, frame.candidate); break;
        case "call_release": if (media.currentCallId === frame.callId) media.close(); break;
        case "call_state":
          if (media.isActive && (frame.state.phase === "ended" || frame.state.phase === "idle"
            || (frame.state.callId && media.currentCallId !== frame.state.callId)
            || (frame.state.phase === "active" && frame.state.seat !== "dex" && !frame.state.movingTo))) media.close();
          break;
      }
      if (added) {
        setTab("chats");
        socket.send({ t: "open_chat", peer: added });
      }
      if (edited) socket.send({ t: "request_contact_detail", peer: edited });
      if (deleted && wasOpen) socket.send({ t: "close_chat", peer: deleted });
      if (changedIp) socket.send({ t: "request_contact_detail", peer: changedIp });
      if (frame.t === "done" && frame.what === "delete_call_logs") socket.send({ t: "request_call_logs", limit: 200 });
      if (frame.t === "call_state" && frame.state.phase === "ended" && store.state.tab === "calls") {
        socket.send({ t: "request_call_logs", limit: 200 });
      }
    },
    onStatus(status) {
      store.actions.setConnection(status);
      if (status === "unauthorized") {
        cleanupActivity();
        store.actions.reset();
      } else if (status === "reconnecting") {
        reconnectChecks += 1;
        if (reconnectChecks % 3 === 0) void recheckSession();
      } else if (status === "limit") {
        store.actions.addToast("warning", "Client limit reached on the phone. Retrying…");
      }
    },
    onOpen() {
      reconnectChecks = 0;
      if (store.state.openPeer) socket.send({ t: "open_chat", peer: store.state.openPeer });
      if (store.state.openPeer && store.state.isChatInfoOpen) {
        socket.send({ t: "request_contact_detail", peer: store.state.openPeer });
        socket.send({ t: "request_chat_media", peer: store.state.openPeer });
        socket.send({ t: "request_chat_links", peer: store.state.openPeer });
      }
      sendWatch();
      if (store.state.tab === "calls") socket.send({ t: "request_call_logs", limit: 200 });
      if (store.state.tab === "contacts" && store.state.selectedContact) {
        socket.send({ t: "request_contact_detail", peer: store.state.selectedContact });
      }
    },
    onError(message) { store.actions.addToast("error", message); },
  });

  function sendWatch(): void {
    const sections = store.state.tab === "settings" ? watchedSections[store.state.settingsTab] ?? [] : [];
    socket.send({ t: "watch", sections });
  }

  function beep(): void {
    try {
      const context = new AudioContext();
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      oscillator.frequency.value = 880;
      gain.gain.value = 0.04;
      oscillator.connect(gain);
      gain.connect(context.destination);
      oscillator.start();
      window.setTimeout(() => { oscillator.stop(); void context.close(); }, 120);
    } catch { store.actions.addToast("warning", "This browser could not play the notification sound."); }
  }

  function notify(title: string, body: string, tag: string, isSound: boolean): void {
    if (!document.hidden || typeof Notification === "undefined" || Notification.permission !== "granted") return;
    try {
      const notification = new Notification(title, { body, tag });
      if (isSound) beep();
      notification.onclick = () => { window.focus(); notification.close(); };
    } catch { store.actions.addToast("warning", "This browser could not show a notification."); }
  }

  function notifyNewMessages(previous: typeof store.state.chats, next: typeof store.state.chats): void {
    if (!document.hidden || previous.length === 0) return;
    const profile = shownProfile(store.state.profileEdits, store.state.settings?.dexProfile ?? {});
    if (!profile.notificationsEnabled) return;
    const before = new Map(previous.map((chat) => [chat.peer, chat]));
    for (const chat of next) {
      if (chat.lastDir !== "in" || chat.isMuted || !chat.unread || before.get(chat.peer)?.lastTs === chat.lastTs) continue;
      const own = store.state.contacts.find((contact) => contact.ip === chat.peer)?.notifications;
      const override = own?.useGlobal === false ? own : null;
      if (override?.messages === false) continue;
      const isPreview = override?.preview ?? profile.notificationPreview;
      notify(chat.label, isPreview ? chat.lastBody ?? "New message" : "New message", `msg-${chat.peer}`, override?.sound ?? profile.notificationSound);
    }
  }

  async function recheckSession(): Promise<void> {
    const result = await session();
    if (!disposed && result.kind === "none") {
      socket.stop();
      cleanupActivity();
      store.actions.reset();
    }
  }

  async function boot(): Promise<void> {
    const result = await session();
    if (disposed) return;
    if (result.kind === "active") {
      store.actions.setScreen("app");
      socket.start();
    } else if (result.kind === "none") {
      store.actions.setLogin(null);
    } else {
      store.actions.setLogin(`Can't reach the phone: ${result.message}`);
    }
  }

  async function signIn(username: string, password: string): Promise<void> {
    if (store.state.isLoginBusy) return;
    store.actions.setLogin(null, true);
    const result = await login(username.trim(), password);
    if (disposed) return;
    switch (result.kind) {
      case "ok": {
        const current = await session();
        if (disposed) return;
        if (current.kind === "active") {
          store.actions.setScreen("app");
          socket.start();
        } else {
          store.actions.setLogin(current.kind === "failed" ? current.message : "The phone did not keep the session.");
        }
        break;
      }
      case "wrong": store.actions.setLogin("Wrong username or password."); break;
      case "locked": store.actions.setLogin("Too many attempts. Try again later."); break;
      case "client-limit": store.actions.setLogin("The phone has reached its browser limit."); break;
      case "failed": store.actions.setLogin(result.message); break;
    }
  }

  async function signOut(): Promise<void> {
    if (!(await logout())) {
      if (!disposed) store.actions.addToast("error", "Could not log out from the phone.");
      return;
    }
    if (disposed) return;
    socket.stop();
    cleanupActivity();
    store.actions.reset();
  }

  function send(frame: ClientFrame): boolean {
    const sent = socket.send(frame);
    if (!sent && frame.t !== "ping" && frame.t !== "typing") {
      store.actions.addToast("warning", "Not connected to the phone right now.");
    }
    return sent;
  }

  function openChat(peer: string): void {
    if (store.state.openPeer !== peer) {
      stopTyping();
      discardRecording();
    }
    store.actions.openChat(peer);
    send({ t: "open_chat", peer });
  }

  function closeChat(): void {
    const peer = store.state.openPeer;
    stopTyping();
    discardRecording();
    store.actions.closeChat();
    if (peer) send({ t: "close_chat", peer });
  }

  function stopTyping(): void {
    if (typingTimer !== null) window.clearTimeout(typingTimer);
    typingTimer = null;
    if (typingPeer) socket.send({ t: "typing", peer: typingPeer, isTyping: false });
    typingPeer = null;
  }

  function typing(value: string): void {
    const peer = store.state.openPeer;
    const contact = store.state.contacts.find((item) => item.ip === peer) ?? store.state.view?.contact;
    const isEnabled = contact?.privacy?.sendTypingIndicators
      ?? shownProfile(store.state.profileEdits, store.state.settings?.dexProfile ?? {}).sendTypingIndicators;
    if (!peer || !isEnabled || !value.trim()) {
      stopTyping();
      return;
    }
    if (typingPeer && typingPeer !== peer) stopTyping();
    const now = Date.now();
    if (typingPeer !== peer || now - lastTypingSentAt > 3_000) {
      socket.send({ t: "typing", peer, isTyping: true });
      typingPeer = peer;
      lastTypingSentAt = now;
    }
    if (typingTimer !== null) window.clearTimeout(typingTimer);
    typingTimer = window.setTimeout(stopTyping, 4_000);
  }

  function openChatInfo(): void {
    const peer = store.state.openPeer;
    if (!peer) return;
    store.actions.setChatInfoOpen(true);
    send({ t: "request_contact_detail", peer });
    send({ t: "request_chat_media", peer });
    send({ t: "request_chat_links", peer });
  }

  function selectContact(peer: string | null): void {
    store.actions.selectContact(peer);
    if (peer) send({ t: "request_contact_detail", peer });
  }

  function setTab(tab: Tab): void {
    if (tab !== "chats") discardRecording();
    store.actions.setTab(tab);
    sendWatch();
    if (tab === "calls") send({ t: "request_call_logs", limit: 200 });
  }

  function setSettingsTab(tab: SettingsTab): void {
    store.actions.setSettingsTab(tab);
    sendWatch();
  }

  function addContact(): void {
    const dialog = store.state.dialog;
    if (dialog?.kind !== "add-contact" || dialog.isBusy) return;
    const result = newContactProblem(dialog.ip, store.state.me?.ip ?? null, (ip) => {
      const contact = store.state.contacts.find((item) => item.ip === ip);
      return contact ? contactLabel(contact) : null;
    });
    if (result.problem) {
      store.actions.setAddContactError(result.problem);
      return;
    }
    if (send({ t: "contact_add", peer: result.ip, nickname: dialog.nickname.trim(), notes: dialog.notes.trim() })) {
      store.actions.startAddContact(result.ip);
    }
  }

  function editContact(): void {
    const dialog = store.state.dialog;
    if (dialog?.kind !== "edit-contact" || dialog.isBusy) return;
    if (send({ t: "contact_save", peer: dialog.peer, nickname: dialog.nickname.trim(), notes: dialog.notes.trim() })) {
      store.actions.startEditContact();
    }
  }

  function deleteContact(): void {
    const dialog = store.state.dialog;
    if (dialog?.kind !== "delete-contact" || dialog.isBusy) return;
    if (send({ t: "contact_delete", peer: dialog.peer })) store.actions.startDeleteContact();
  }

  function changeContactIp(): void {
    const dialog = store.state.dialog;
    if (dialog?.kind !== "change-contact-ip" || dialog.isBusy) return;
    const result = newContactProblem(dialog.newIp, store.state.me?.ip ?? null, (ip) => {
      const contact = store.state.contacts.find((item) => item.ip === ip);
      return contact ? contactLabel(contact) : null;
    });
    if (result.problem) { store.actions.setChangeContactIpError(result.problem); return; }
    if (send({ t: "contact_change_ip", peer: dialog.peer, newIp: result.ip })) store.actions.startChangeContactIp(result.ip);
  }

  function sendText(): void {
    const peer = store.state.openPeer;
    const body = store.state.composerDraft.trim();
    if (!peer || !body || store.state.pendingText || store.state.pendingEdit) return;
    const contact = store.state.contacts.find((item) => item.ip === peer) ?? store.state.view?.contact ?? null;
    if (composerBlock(contact, store.state.isTunnelOn) !== "none") return;
    if (new TextEncoder().encode(body).length > 16 * 1024) {
      store.actions.addToast("warning", "That message is too long.");
      return;
    }
    const isCovered = store.state.isComposerCovered;
    const mode = store.state.composerMode;
    if (mode?.kind === "edit") {
      if (send({ t: "edit", messageId: mode.message.id, body })) store.actions.startEdit(mode.message.id, body);
      return;
    }
    const requestId = String(nextTextRequestId++);
    if (send({ t: "send_text", peer, body, covered: isCovered, requestId,
      ...(mode?.kind === "reply" ? { replyTo: mode.message.id } : {}) })) {
      stopTyping();
      store.actions.startText(peer, body, requestId);
    }
  }

  function startCall(peer: string, video: boolean): void {
    if (!media.isSupported) { store.actions.addToast("error", "Calls need a secure origin and a modern browser."); return; }
    const contact = store.state.contacts.find((item) => item.ip === peer) ?? null;
    const block = composerBlock(contact, store.state.isTunnelOn);
    if (block !== "none") {
      store.actions.addToast("warning", block === "tunnel-off" ? "Turn on the Nebula tunnel to call." :
        block === "blocked" ? "Unblock this contact before calling." : "Unarchive this chat before calling.");
      return;
    }
    if (store.state.call.phase && store.state.call.phase !== "idle" && store.state.call.phase !== "ended") {
      store.actions.addToast("warning", "A call is already in progress."); return;
    }
    send({ t: "call_start", peer, video });
  }

  function acceptCall(): void {
    const callId = store.state.call.callId;
    if (callId && media.isSupported) send({ t: "call_accept", callId });
  }

  function rejectCall(): void {
    const callId = store.state.call.callId;
    if (callId) send({ t: "call_reject", callId });
  }

  function endCall(): void {
    const callId = store.state.call.callId;
    if (callId) send({ t: "call_end", callId });
  }

  function toggleMute(): void {
    media.setMuted(!media.isMuted);
    store.actions.setCallControls(media.isMuted, media.isCameraOn);
  }

  async function toggleCamera(): Promise<void> {
    const callId = store.state.call.callId;
    if (!callId) return;
    const isOn = !media.isCameraOn;
    if (await media.setCamera(isOn)) send({ t: "call_cam", callId, isOn });
    else store.actions.addToast("warning", "Camera is not available.");
    store.actions.setCallControls(media.isMuted, media.isCameraOn);
  }

  function moveCallToPhone(): void {
    const callId = store.state.call.callId;
    if (callId && store.state.call.seat === "dex" && store.state.call.phase === "active") send({ t: "call_move_to_phone", callId });
  }

  function editProfile(change: (current: EffectiveProfile) => EffectiveProfile): void {
    const current = shownProfile(store.state.profileEdits, store.state.settings?.dexProfile ?? {});
    const next = change(current);
    if (send({ t: "set_settings", patch: { dexProfile: next } })) store.actions.stageProfileEdit(next);
  }

  async function attach(files: FileList | File[]): Promise<void> {
    const generation = activityGeneration;
    const peer = store.state.openPeer;
    if (!peer || files.length === 0) return;
    if (activeUploadCount + files.length > 3) {
      store.actions.addToast("warning", "Wait for the current uploads to finish.");
      return;
    }
    const contact = store.state.contacts.find((item) => item.ip === peer) ?? store.state.view?.contact ?? null;
    if (composerBlock(contact, store.state.isTunnelOn) !== "none") return;
    const mode = store.state.composerMode;
    const replyTo = mode?.kind === "reply" ? mode.message.id : undefined;
    const isCovered = store.state.isComposerCovered;
    store.actions.setComposerMode(null);
    await Promise.all(Array.from(files, async (file) => {
      const id = nextUploadId++;
      activeUploadCount += 1;
      store.actions.addUpload({ id, name: file.name, percent: 0, error: null });
      const mime = file.type || "application/octet-stream";
      let dimensions: { width: number; height: number } | undefined;
      if (mime.startsWith("image/")) {
        try {
          const image = await createImageBitmap(file);
          dimensions = { width: image.width, height: image.height };
          image.close();
        } catch { dimensions = undefined; }
      }
      if (disposed || generation !== activityGeneration) {
        activeUploadCount -= 1;
        store.actions.dismissUpload(id);
        return;
      }
      const result = await upload({ peer, name: file.name, mime, body: file, isCovered, isVoice: false,
        ...(replyTo ? { replyTo } : {}), ...dimensions },
      (abort) => uploadHandles.set(id, abort), (percent) => store.actions.setUploadPercent(id, percent));
      uploadHandles.delete(id);
      activeUploadCount -= 1;
      if (result.kind === "failed") store.actions.setUploadError(id, result.message);
      else store.actions.dismissUpload(id);
    }));
  }

  function cancelUpload(id: number): void {
    const abort = uploadHandles.get(id);
    if (abort) abort();
    else store.actions.dismissUpload(id);
  }

  async function startRecording(): Promise<void> {
    const peer = store.state.openPeer;
    const generation = recordingGeneration;
    if (!peer || store.state.recording || voiceUploadId !== null) return;
    const contact = store.state.contacts.find((item) => item.ip === peer) ?? store.state.view?.contact ?? null;
    if (composerBlock(contact, store.state.isTunnelOn) !== "none") return;
    if (!recorder.isSupported) {
      store.actions.addToast("warning", "Voice messages need a secure origin and a modern browser.");
      return;
    }
    if (await recorder.start()) {
      if (disposed || recordingGeneration !== generation || store.state.openPeer !== peer || store.state.tab !== "chats") recorder.cancel();
      else store.actions.setRecording({ kind: "live", startedAt: Date.now() });
    }
    else if (!disposed && recordingGeneration === generation && store.state.openPeer === peer && store.state.tab === "chats") store.actions.addToast("warning", "Microphone is not available.");
  }

  async function stopRecording(): Promise<void> {
    if (store.state.recording?.kind !== "live") return;
    const generation = recordingGeneration;
    const clip = await recorder.stop();
    if (disposed || recordingGeneration !== generation) return;
    if (!clip) {
      store.actions.setRecording(null);
      store.actions.addToast("warning", "Nothing was recorded.");
      return;
    }
    readyClip = clip;
    store.actions.setRecording({ kind: "ready", url: URL.createObjectURL(clip.blob), durationMs: clip.durationMs });
  }

  function discardRecording(): void {
    recordingGeneration += 1;
    if (voiceUploadId !== null) uploadHandles.get(voiceUploadId)?.();
    recorder.cancel();
    if (store.state.recording?.kind === "ready") URL.revokeObjectURL(store.state.recording.url);
    readyClip = null;
    store.actions.setRecording(null);
  }

  async function sendRecording(): Promise<void> {
    const peer = store.state.openPeer;
    if (!peer || voiceUploadId !== null) return;
    const generation = recordingGeneration;
    if (store.state.recording?.kind === "live") await stopRecording();
    if (disposed || recordingGeneration !== generation || store.state.openPeer !== peer) return;
    const clip = readyClip;
    if (!clip) return;
    const contact = store.state.contacts.find((item) => item.ip === peer) ?? store.state.view?.contact ?? null;
    if (composerBlock(contact, store.state.isTunnelOn) !== "none") return;
    if (activeUploadCount >= 3) {
      store.actions.addToast("warning", "Wait for the current uploads to finish.");
      return;
    }
    const mode = store.state.composerMode;
    const id = nextUploadId++;
    const name = `Voice message.${voiceExtension(clip.mime)}`;
    voiceUploadId = id;
    activeUploadCount += 1;
    store.actions.addUpload({ id, name, percent: 0, error: null, isVoice: true });
    const result = await upload({ peer, name, mime: clip.mime, body: clip.blob, isVoice: true,
      isCovered: store.state.isComposerCovered, durationMs: clip.durationMs,
      ...(mode?.kind === "reply" ? { replyTo: mode.message.id } : {}) },
    (abort) => uploadHandles.set(id, abort), (percent) => store.actions.setUploadPercent(id, percent));
    uploadHandles.delete(id);
    activeUploadCount -= 1;
    voiceUploadId = null;
    if (result.kind === "failed") store.actions.setUploadError(id, result.message);
    else {
      store.actions.dismissUpload(id);
      if (result.kind === "done" && readyClip === clip) {
        discardRecording();
        if (mode?.kind === "reply" && store.state.composerMode?.kind === "reply"
          && store.state.composerMode.message.id === mode.message.id) store.actions.setComposerMode(null);
      }
    }
  }

  function cleanupActivity(): void {
    activityGeneration += 1;
    stopTyping();
    for (const abort of uploadHandles.values()) abort();
    uploadHandles.clear();
    voiceUploadId = null;
    discardRecording();
    media.close();
  }

  function dispose(): void {
    disposed = true;
    cleanupActivity();
    socket.stop();
    store.actions.dispose();
  }

  return { ...store, boot, signIn, signOut, send, openChat, closeChat, openChatInfo, selectContact, setTab, setSettingsTab, addContact, editContact, deleteContact, changeContactIp, sendText, typing, editProfile, attach, cancelUpload, startRecording, stopRecording, discardRecording, sendRecording, startCall, acceptCall, rejectCall, endCall, toggleMute, toggleCamera, moveCallToPhone, dispose };
}

export type DexRuntime = ReturnType<typeof createDexRuntime>;

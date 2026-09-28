import { createStore, reconcile } from "solid-js/store";
import { feedbackForDone } from "../logic/replies";
import { archivedViewAfterUpdate, archivedViewForOpening } from "../logic/chat-lists";
import { askedProfile, receivedProfile, type EffectiveProfile, type ProfileEdits } from "../logic/effective";
import type { ServerFrame } from "../wire/frames";
import type {
  DexAccount, DexCallLog, DexCallState, DexChat, DexChatLink, DexChatView, DexContact,
  DexContactDetail, DexDiagnostics, DexIdentity, DexMessage, DexNetwork, DexNoticeLevel,
  DexPingResult, DexPresence, DexQueue, DexSettings, DexStorage, DexUpdates,
} from "../wire/models";

export type Screen = "loading" | "login" | "app";
export type Tab = "chats" | "contacts" | "calls" | "settings";
export type SettingsTab = "account" | "status" | "appearance" | "chats" | "notifications"
  | "privacy" | "network" | "storage" | "diagnostics";
export type Connection = "connecting" | "connected" | "reconnecting" | "unauthorized" | "limit";

export interface Toast { id: number; level: DexNoticeLevel; message: string }
export interface DexUpload { id: number; name: string; percent: number; error: string | null; isVoice?: boolean }
export type RecordingState = { kind: "live"; startedAt: number } | { kind: "ready"; url: string; durationMs: number } | null;
export type ServerSignal = Extract<ServerFrame, { t: "call_media" | "call_sdp" | "call_ice" | "call_release" }>;
export interface AddContactDialogState {
  kind: "add-contact";
  ip: string;
  nickname: string;
  notes: string;
  error: string | null;
  isBusy: boolean;
}
export interface EditContactDialogState {
  kind: "edit-contact";
  peer: string;
  nickname: string;
  notes: string;
  error: string | null;
  isBusy: boolean;
}
export interface DeleteContactDialogState {
  kind: "delete-contact";
  peer: string;
  label: string;
  error: string | null;
  isBusy: boolean;
}
export interface ChangeContactIpDialogState {
  kind: "change-contact-ip";
  peer: string;
  newIp: string;
  error: string | null;
  isBusy: boolean;
}
export type ComposerMode = { kind: "reply"; message: DexMessage } | { kind: "edit"; message: DexMessage } | null;

export interface DexState {
  screen: Screen;
  loginError: string | null;
  isLoginBusy: boolean;
  me: DexIdentity | null;
  clientId: string | null;
  freeBytes: number;
  connection: Connection;
  chats: DexChat[];
  contacts: DexContact[];
  presence: Record<string, DexPresence>;
  typing: string[];
  queues: DexQueue[];
  isTunnelOn: boolean | null;
  openPeer: string | null;
  isChatInfoOpen: boolean;
  view: DexChatView | null;
  composerDraft: string;
  composerMode: ComposerMode;
  isComposerCovered: boolean;
  pendingText: { peer: string; body: string; requestId: string } | null;
  pendingEdit: { messageId: string; body: string } | null;
  uploads: DexUpload[];
  recording: RecordingState;
  revealedMessages: string[];
  revealPromptId: string | null;
  isLoadingMore: boolean;
  call: DexCallState;
  callLocalStream: MediaStream | null;
  callRemoteStream: MediaStream | null;
  isCallMuted: boolean;
  isCallCameraOn: boolean;
  callSignals: ServerSignal[];
  settings: DexSettings | null;
  profileEdits: ProfileEdits;
  account: DexAccount | null;
  network: DexNetwork | null;
  storage: DexStorage | null;
  diagnostics: DexDiagnostics | null;
  updates: DexUpdates | null;
  callLogs: DexCallLog[];
  selectedContact: string | null;
  contactDetail: DexContactDetail | null;
  chatMediaPeer: string | null;
  chatMedia: DexMessage[];
  chatLinksPeer: string | null;
  chatLinks: DexChatLink[];
  chatSearchResults: DexMessage[] | null;
  pings: Record<string, DexPingResult>;
  tab: Tab;
  settingsTab: SettingsTab;
  isArchivedView: boolean;
  search: string;
  contactSearch: string;
  dialog: AddContactDialogState | EditContactDialogState | DeleteContactDialogState | ChangeContactIpDialogState | null;
  toasts: Toast[];
  refusals: number;
}

function initialState(): DexState {
  return {
    screen: "loading", loginError: null, isLoginBusy: false,
    me: null, clientId: null, freeBytes: 0, connection: "connecting",
    chats: [], contacts: [], presence: {}, typing: [], queues: [], isTunnelOn: null,
    openPeer: null, isChatInfoOpen: false, view: null, composerDraft: "", composerMode: null, isComposerCovered: false, pendingText: null, pendingEdit: null,
    uploads: [], recording: null, revealedMessages: [], revealPromptId: null,
    isLoadingMore: false, call: {}, callLocalStream: null, callRemoteStream: null,
    isCallMuted: false, isCallCameraOn: false, callSignals: [],
    settings: null, profileEdits: { pending: null },
    account: null, network: null, storage: null, diagnostics: null, updates: null,
    callLogs: [], selectedContact: null, contactDetail: null,
    chatMediaPeer: null, chatMedia: [], chatLinksPeer: null, chatLinks: [],
    chatSearchResults: null, pings: {}, tab: "chats", settingsTab: "account",
    isArchivedView: false, search: "", contactSearch: "", dialog: null, toasts: [], refusals: 0,
  };
}

export function createDexStore() {
  const [state, setState] = createStore<DexState>(initialState());
  const toastTimers = new Set<number>();
  let nextToastId = 1;

  function dismissToast(id: number): void {
    setState("toasts", (items) => items.filter((item) => item.id !== id));
  }

  function addToast(level: DexNoticeLevel, message: string): void {
    if (state.toasts.some((item) => item.level === level && item.message === message)) return;
    const id = nextToastId++;
    setState("toasts", (items) => [...items, { id, level, message }].slice(-4));
    const timer = window.setTimeout(() => {
      toastTimers.delete(timer);
      dismissToast(id);
    }, level === "error" ? 8_000 : 4_500);
    toastTimers.add(timer);
  }

  function receive(frame: ServerFrame): void {
    switch (frame.t) {
      case "pong": break;
      case "hello":
        setState({ me: frame.me, clientId: frame.clientId, freeBytes: frame.freeBytes, screen: "app" });
        break;
      case "chats":
        setState("isArchivedView", archivedViewAfterUpdate(state.chats, frame.items, state.openPeer, state.isArchivedView));
        setState("chats", reconcile(frame.items, { key: "peer" }));
        break;
      case "contacts":
        setState("contacts", reconcile(frame.items, { key: "ip" }));
        if (state.selectedContact && !frame.items.some((item) => item.ip === state.selectedContact)) {
          setState({ selectedContact: null, contactDetail: null });
        }
        break;
      case "chat":
        if (frame.view.peer === state.openPeer) {
          const previous = state.view?.peer === frame.view.peer ? state.view : null;
          const first = frame.view.messages[0];
          const ids = new Set(frame.view.messages.map((item) => item.id));
          const older = previous?.messages.filter((item) => first &&
            (item.ts < first.ts || (item.ts === first.ts && item.id < first.id)) && !ids.has(item.id)) ?? [];
          const view = older.length > 0
            ? { ...frame.view, messages: [...older, ...frame.view.messages], hasMore: previous?.hasMore ?? frame.view.hasMore }
            : frame.view;
          setState("view", reconcile(view, { key: "id" }));
          setState("isLoadingMore", false);
        }
        break;
      case "chat_more":
        if (frame.peer === state.view?.peer) {
          const ids = new Set(state.view.messages.map((item) => item.id));
          const earlier = frame.messages.filter((item) => !ids.has(item.id));
          setState("view", "messages", reconcile([...earlier, ...state.view.messages], { key: "id" }));
          setState("view", "hasMore", frame.hasMore);
          setState("isLoadingMore", false);
        }
        break;
      case "presence": setState("presence", reconcile(frame.items)); break;
      case "typing": setState("typing", frame.peers); break;
      case "queues": setState("queues", reconcile(frame.items, { key: "peer" })); break;
      case "tunnel": setState("isTunnelOn", frame.isOn); break;
      case "notice": addToast(frame.level, frame.message); break;
      case "error":
        setState("refusals", (count) => count + 1);
        if (frame.ref === "load_more") setState("isLoadingMore", false);
        if (frame.ref === "send_text" && state.pendingText && frame.requestId === state.pendingText.requestId) {
          setState("pendingText", null);
          addToast("error", `The message was not sent: ${frame.message}. Your text is still in the box.`);
          break;
        }
        if (state.pendingEdit && frame.ref === state.pendingEdit.messageId) {
          setState("pendingEdit", null);
          addToast("error", `The edit was not saved: ${frame.message}. Your text is still in the box.`);
          break;
        }
        if (frame.ref === "contact_add" && state.dialog?.kind === "add-contact" && state.dialog.isBusy) {
          setState("dialog", { ...state.dialog, error: frame.message, isBusy: false });
          break;
        }
        if (frame.ref === "contact_save" && state.dialog?.kind === "edit-contact" && state.dialog.isBusy) {
          setState("dialog", { ...state.dialog, error: frame.message, isBusy: false });
          break;
        }
        if (frame.ref === "contact_delete" && state.dialog?.kind === "delete-contact" && state.dialog.isBusy) {
          setState("dialog", { ...state.dialog, error: frame.message, isBusy: false });
          break;
        }
        if (frame.ref === "contact_change_ip" && state.dialog?.kind === "change-contact-ip" && state.dialog.isBusy) {
          setState("dialog", { ...state.dialog, error: frame.message, isBusy: false });
          break;
        }
        if (frame.ref === "set_settings") setState("profileEdits", { pending: null });
        addToast("error", frame.message);
        break;
      case "call_state": setState("call", reconcile(frame.state)); break;
      case "call_media":
      case "call_sdp":
      case "call_ice":
      case "call_release":
        setState("callSignals", (signals) => [...signals, frame].slice(-32));
        break;
      case "settings":
        if (frame.settings.dexProfile) {
          setState("profileEdits", receivedProfile(state.profileEdits, frame.settings.dexProfile));
        }
        setState("settings", reconcile(frame.settings));
        break;
      case "account": setState("account", reconcile(frame.account)); break;
      case "network": setState("network", reconcile(frame.network)); break;
      case "storage": setState("storage", reconcile(frame.storage)); break;
      case "diagnostics": setState("diagnostics", reconcile(frame.diagnostics)); break;
      case "updates": setState("updates", reconcile(frame.updates)); break;
      case "call_logs": setState("callLogs", reconcile(frame.items, { key: "id" })); break;
      case "contact_detail":
        if (state.selectedContact === frame.detail.contact.ip || (state.isChatInfoOpen && state.openPeer === frame.detail.contact.ip)) setState("contactDetail", reconcile(frame.detail));
        break;
      case "chat_media":
        if (state.openPeer === frame.peer) {
          setState("chatMediaPeer", frame.peer);
          setState("chatMedia", reconcile(frame.items, { key: "id" }));
        }
        break;
      case "chat_links":
        if (state.openPeer === frame.peer) {
          setState("chatLinksPeer", frame.peer);
          setState("chatLinks", reconcile(frame.items, { key: "messageId" }));
        }
        break;
      case "ping_result": setState("pings", frame.result.peer, frame.result); break;
      case "search_results":
        if (state.openPeer === frame.peer) setState("chatSearchResults", reconcile(frame.items, { key: "id" }));
        break;
      case "done": {
        if (frame.what === "edit" && state.pendingEdit && frame.requestId === state.pendingEdit.messageId) {
          const pending = state.pendingEdit;
          if (state.composerMode?.kind === "edit" && state.composerMode.message.id === pending.messageId
            && state.composerDraft.trim() === pending.body) {
            setState("composerDraft", "");
            setState("composerMode", null);
          }
          setState("pendingEdit", null);
        }
        if (frame.what === "send_text" && state.pendingText && frame.requestId === state.pendingText.requestId) {
          const pending = state.pendingText;
          if (pending && state.openPeer === pending.peer && state.composerDraft.trim() === pending.body) {
            setState("composerDraft", "");
            setState("isComposerCovered", false);
            setState("composerMode", null);
          }
          setState("pendingText", null);
        }
        if (frame.what === "contact_add" && state.dialog?.kind === "add-contact" && state.dialog.isBusy) {
          const peer = state.dialog.ip;
          setState("dialog", null);
          actions.openChat(peer);
        }
        if (frame.what === "contact_save" && state.dialog?.kind === "edit-contact" && state.dialog.isBusy) {
          setState("dialog", null);
        }
        if (frame.what === "contact_delete" && state.dialog?.kind === "delete-contact" && state.dialog.isBusy) {
          const peer = state.dialog.peer;
          setState("dialog", null);
          if (state.selectedContact === peer) setState({ selectedContact: null, contactDetail: null });
          if (state.openPeer === peer) actions.closeChat();
        }
        if (frame.what === "contact_change_ip" && state.dialog?.kind === "change-contact-ip" && state.dialog.isBusy) {
          const next = state.dialog.newIp;
          setState("dialog", null);
          setState({ selectedContact: next, contactDetail: null });
        }
        const feedback = feedbackForDone(frame.what, frame.message);
        if (feedback) addToast(feedback.level, feedback.message);
        break;
      }
      default: {
        const unreachable: never = frame;
        return unreachable;
      }
    }
  }

  const actions = {
    receive,
    addToast,
    dismissToast,
    setConnection(connection: Connection): void {
      if (connection === "reconnecting" || connection === "limit" || connection === "unauthorized") {
        setState("profileEdits", { pending: null });
        if (state.pendingText) {
          setState("pendingText", null);
          addToast("warning", "The phone disconnected before confirming your message. Your text is still in the box.");
        }
        if (state.pendingEdit) {
          setState("pendingEdit", null);
          addToast("warning", "The phone disconnected before confirming your edit. Your text is still in the box.");
        }
        if (state.dialog?.isBusy) {
          setState("dialog", { ...state.dialog, isBusy: false,
            error: "The connection to the phone dropped before it answered. Try again." });
        }
      }
      setState("connection", connection);
    },
    stageProfileEdit(next: EffectiveProfile): void { setState("profileEdits", askedProfile(next)); },
    takeCallSignals(): ServerSignal[] {
      const signals = [...state.callSignals];
      setState("callSignals", []);
      return signals;
    },
    setCallLocalStream(stream: MediaStream | null): void { setState("callLocalStream", stream); },
    setCallRemoteStream(stream: MediaStream | null): void { setState("callRemoteStream", stream); },
    setCallControls(isMuted: boolean, isCameraOn: boolean): void {
      setState({ isCallMuted: isMuted, isCallCameraOn: isCameraOn });
    },
    setScreen(screen: Screen): void { setState("screen", screen); },
    setLogin(error: string | null, isBusy = false): void {
      setState({ screen: "login", loginError: error, isLoginBusy: isBusy });
    },
    reset(): void { setState(reconcile({ ...initialState(), screen: "login" })); },
    openChat(peer: string): void {
      if (state.openPeer === peer) return;
      const contactArchived = state.contacts.find((item) => item.ip === peer)?.isArchived;
      setState({
        openPeer: peer, isChatInfoOpen: false, view: null, composerDraft: "", composerMode: null, isComposerCovered: false, pendingText: null, pendingEdit: null,
        revealedMessages: [], revealPromptId: null,
        isLoadingMore: false, chatSearchResults: null,
        isArchivedView: archivedViewForOpening(peer, state.chats, contactArchived, state.isArchivedView),
      });
    },
    closeChat(): void {
      setState({ openPeer: null, isChatInfoOpen: false, view: null, composerDraft: "", composerMode: null, isComposerCovered: false, pendingText: null, pendingEdit: null,
        revealedMessages: [], revealPromptId: null,
        isLoadingMore: false, chatSearchResults: null });
    },
    setComposerDraft(value: string): void { setState("composerDraft", value); },
    setChatInfoOpen(value: boolean): void { setState("isChatInfoOpen", value); },
    addUpload(upload: DexUpload): void { setState("uploads", (items) => [...items, upload]); },
    setUploadPercent(id: number, percent: number): void {
      setState("uploads", (items) => items.map((item) => item.id === id ? { ...item, percent } : item));
    },
    setUploadError(id: number, error: string): void {
      setState("uploads", (items) => items.map((item) => item.id === id ? { ...item, error } : item));
    },
    dismissUpload(id: number): void { setState("uploads", (items) => items.filter((item) => item.id !== id)); },
    setRecording(recording: RecordingState): void { setState("recording", recording); },
    setLoadingMore(value: boolean): void { setState("isLoadingMore", value); },
    setComposerMode(mode: ComposerMode): void {
      setState("composerMode", mode);
      if (mode?.kind === "edit") setState("composerDraft", mode.message.body);
    },
    toggleComposerCover(): void { setState("isComposerCovered", (isCovered) => !isCovered); },
    revealMessage(id: string): void {
      if (!state.revealedMessages.includes(id)) setState("revealedMessages", (ids) => [...ids, id]);
      setState("revealPromptId", null);
    },
    askReveal(id: string): void { setState("revealPromptId", id); },
    cancelReveal(): void { setState("revealPromptId", null); },
    startText(peer: string, body: string, requestId: string): void {
      setState("pendingText", { peer, body, requestId });
    },
    startEdit(messageId: string, body: string): void { setState("pendingEdit", { messageId, body }); },
    selectContact(peer: string | null): void {
      setState({ selectedContact: peer, contactDetail: null });
    },
    setArchivedView(isShown: boolean): void { setState("isArchivedView", isShown); },
    setSearch(value: string): void { setState("search", value); },
    setContactSearch(value: string): void { setState("contactSearch", value); },
    openAddContact(): void {
      setState("dialog", { kind: "add-contact", ip: "", nickname: "", notes: "", error: null, isBusy: false });
    },
    openEditContact(contact: DexContact): void {
      setState("dialog", { kind: "edit-contact", peer: contact.ip,
        nickname: contact.nickname ?? "", notes: contact.notes ?? "", error: null, isBusy: false });
    },
    openDeleteContact(contact: DexContact, label: string): void {
      setState("dialog", { kind: "delete-contact", peer: contact.ip, label, error: null, isBusy: false });
    },
    openChangeContactIp(contact: DexContact): void {
      setState("dialog", { kind: "change-contact-ip", peer: contact.ip, newIp: contact.ip, error: null, isBusy: false });
    },
    closeDialog(): void { setState("dialog", null); },
    updateAddContact(field: "ip" | "nickname" | "notes", value: string): void {
      if (state.dialog?.kind === "add-contact") {
        setState("dialog", { ...state.dialog, [field]: value, error: null });
      }
    },
    setAddContactError(message: string): void {
      if (state.dialog?.kind === "add-contact") setState("dialog", { ...state.dialog, error: message, isBusy: false });
    },
    startAddContact(ip: string): void {
      if (state.dialog?.kind === "add-contact") setState("dialog", { ...state.dialog, ip, error: null, isBusy: true });
    },
    updateEditContact(field: "nickname" | "notes", value: string): void {
      if (state.dialog?.kind === "edit-contact") {
        setState("dialog", { ...state.dialog, [field]: value, error: null });
      }
    },
    startEditContact(): void {
      if (state.dialog?.kind === "edit-contact") setState("dialog", { ...state.dialog, error: null, isBusy: true });
    },
    startDeleteContact(): void {
      if (state.dialog?.kind === "delete-contact") setState("dialog", { ...state.dialog, error: null, isBusy: true });
    },
    updateChangeContactIp(value: string): void {
      if (state.dialog?.kind === "change-contact-ip") setState("dialog", { ...state.dialog, newIp: value, error: null });
    },
    setChangeContactIpError(message: string): void {
      if (state.dialog?.kind === "change-contact-ip") setState("dialog", { ...state.dialog, error: message, isBusy: false });
    },
    startChangeContactIp(newIp: string): void {
      if (state.dialog?.kind === "change-contact-ip") setState("dialog", { ...state.dialog, newIp, error: null, isBusy: true });
    },
    setTab(tab: Tab): void { setState("tab", tab); },
    setSettingsTab(tab: SettingsTab): void { setState("settingsTab", tab); },
    dispose(): void {
      for (const timer of toastTimers) window.clearTimeout(timer);
      toastTimers.clear();
    },
  };

  return { state, actions };
}

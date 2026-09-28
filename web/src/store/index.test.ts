import { afterEach, expect, test } from "vitest";
import { createDexStore } from "./index";

const stores: ReturnType<typeof createDexStore>[] = [];

function store() {
  const value = createDexStore();
  stores.push(value);
  return value;
}

afterEach(() => {
  for (const value of stores.splice(0)) value.actions.dispose();
});

test("phone snapshots keep the same chat row and replace its fields", () => {
  const { state, actions } = store();
  actions.receive({ t: "chats", items: [{ peer: "10.0.0.2", label: "Alice", unread: 1 }] });
  const row = state.chats[0];
  actions.receive({ t: "chats", items: [{ peer: "10.0.0.2", label: "Alice", unread: 2 }] });
  expect(state.chats[0]).toBe(row);
  expect(state.chats[0]?.unread).toBe(2);
});

test("late chat frames cannot replace the newly opened chat", () => {
  const { state, actions } = store();
  actions.openChat("10.0.0.3");
  actions.receive({
    t: "chat", view: {
      peer: "10.0.0.2", contact: { ip: "10.0.0.2", label: "Alice", name: "Alice" },
      messages: [], hasMore: false,
    },
  });
  expect(state.view).toBeNull();
});

test("routine acknowledgements never show Done", () => {
  const { state, actions } = store();
  actions.receive({ t: "done", what: "watch" });
  actions.receive({ t: "done", what: "set_settings" });
  expect(state.toasts).toEqual([]);
  actions.receive({ t: "done", what: "contact_add" });
  expect(state.toasts[0]?.message).toBe("Contact added.");
});

test("a fresh chat snapshot keeps pages already loaded above it", () => {
  const { state, actions } = store();
  actions.openChat("10.0.0.2");
  const contact = { ip: "10.0.0.2", label: "Alice", name: "Alice" };
  const old = { id: "old", peer: "10.0.0.2", dir: "in" as const, body: "older", ts: 1,
    status: "delivered" as const, kind: "text" as const };
  const recent = { ...old, id: "recent", body: "newer", ts: 2 };
  actions.receive({ t: "chat", view: { peer: contact.ip, contact, messages: [recent], hasMore: true } });
  actions.receive({ t: "chat_more", peer: contact.ip, messages: [old], hasMore: false });
  actions.receive({ t: "chat", view: { peer: contact.ip, contact, messages: [recent], hasMore: true } });
  expect(state.view?.messages.map((item) => item.id)).toEqual(["old", "recent"]);
  expect(state.view?.hasMore).toBe(false);
});

test("loaded messages sharing the first snapshot timestamp stay in the chat", () => {
  const { state, actions } = store();
  const contact = { ip: "10.0.0.2", label: "Alice", name: "Alice" };
  const recent = { id: "b", peer: contact.ip, dir: "in" as const, body: "recent", ts: 1,
    status: "received" as const, kind: "text" as const };
  const older = { ...recent, id: "a", body: "earlier" };
  actions.openChat(contact.ip);
  actions.receive({ t: "chat", view: { peer: contact.ip, contact, messages: [recent], hasMore: true } });
  actions.receive({ t: "chat_more", peer: contact.ip, messages: [older], hasMore: false });
  actions.receive({ t: "chat", view: { peer: contact.ip, contact, messages: [recent], hasMore: true } });
  expect(state.view?.messages.map((item) => item.id)).toEqual(["a", "b"]);
});

test("only the matching text reply clears a draft", () => {
  const { state, actions } = store();
  const contact = { ip: "10.0.0.2", label: "Alice", name: "Alice" };
  actions.openChat(contact.ip);
  actions.setComposerDraft("private note");
  actions.toggleComposerCover();
  actions.startText(contact.ip, "private note", "r2");
  actions.receive({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [
    { id: "other", peer: contact.ip, dir: "out", body: "", ts: 1, status: "sent", kind: "text", isCovered: true },
  ] } });
  actions.receive({ t: "done", what: "send_text", requestId: "r1" });
  expect(state.pendingText?.requestId).toBe("r2");
  expect(state.composerDraft).toBe("private note");
  actions.receive({ t: "done", what: "send_text", requestId: "r2" });
  expect(state.pendingText).toBeNull();
  expect(state.composerDraft).toBe("");
  expect(state.isComposerCovered).toBe(false);
  expect(state.toasts).toEqual([]);
});

test("a refused edit keeps its draft until its own completion", () => {
  const { state, actions } = store();
  const message = { id: "m1", peer: "10.0.0.2", dir: "out" as const, body: "before", ts: 1,
    status: "sent" as const, kind: "text" as const };
  actions.openChat(message.peer);
  actions.setComposerMode({ kind: "edit", message });
  actions.setComposerDraft("after");
  actions.startEdit(message.id, "after");
  actions.receive({ t: "error", ref: message.id, message: "No longer editable" });
  expect(state.pendingEdit).toBeNull();
  expect(state.composerDraft).toBe("after");
  expect(state.composerMode?.kind).toBe("edit");
  actions.startEdit(message.id, "after");
  actions.receive({ t: "done", what: "edit", requestId: "other" });
  expect(state.pendingEdit?.messageId).toBe(message.id);
  actions.receive({ t: "done", what: "edit", requestId: message.id });
  expect(state.pendingEdit).toBeNull();
  expect(state.composerDraft).toBe("");
  expect(state.composerMode).toBeNull();
});

test("an older settings frame cannot erase a pending browser profile edit", () => {
  const { state, actions } = store();
  actions.stageProfileEdit({
    sendReadReceipts: true, sendTypingIndicators: true, coverRevealGate: "tap", themeMode: "system",
    colorTheme: "sky", customAccent: "#7FB7E6", chatTextSize: "medium", messageDensity: "comfortable",
    isEnterToSend: true, notificationsEnabled: true, notificationPreview: true, notificationSound: true,
  });
  actions.receive({ t: "settings", settings: { dexProfile: { isEnterToSend: false } } });
  expect(state.profileEdits.pending?.isEnterToSend).toBe(true);
  actions.receive({ t: "settings", settings: { dexProfile: { isEnterToSend: true } } });
  expect(state.profileEdits.pending).toBeNull();
});

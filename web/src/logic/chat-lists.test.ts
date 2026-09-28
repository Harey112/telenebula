import { expect, test } from "vitest";
import { archivedSummary, archivedViewAfterUpdate, chatRows } from "./chat-lists";
import type { DexChat } from "../wire/models";

const regular: DexChat = { peer: "10.0.0.1", label: "Alice", lastTs: 3, unread: 1 };
const archived: DexChat = { peer: "10.0.0.2", label: "Bob", lastTs: 4, isArchived: true, unread: 2 };

test("archive control counts archived chats but does not mix them into the regular list", () => {
  expect(archivedSummary([regular, archived])).toEqual({ count: 1, unread: 2 });
  expect(chatRows([regular, archived], false, null, "")).toEqual([regular]);
  expect(chatRows([regular, archived], true, null, "")).toEqual([archived]);
});

test("archiving the open chat keeps its row in the visible list", () => {
  expect(archivedViewAfterUpdate([regular], [{ ...regular, isArchived: true }], regular.peer, false)).toBe(true);
});

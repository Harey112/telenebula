import { describe, expect, it } from "vitest";
import { timeline } from "./timeline";
import type { DexMessage } from "../wire/models";

describe("timeline", () => {
  it("keeps one unread divider and starts a new day at the local day boundary", () => {
    const first: DexMessage = { id: "a", peer: "fd00::2", dir: "in", body: "one", ts: Date.UTC(2026, 8, 22, 23), status: "received", kind: "text", isRead: true };
    const second: DexMessage = { ...first, id: "b", body: "two", ts: Date.UTC(2026, 8, 23, 1), isRead: false };
    const third: DexMessage = { ...second, id: "c", body: "three" };
    expect(timeline([first, second, third], Date.UTC(2026, 8, 23, 12)).map((item) => item.kind))
      .toEqual(["day", "message", "day", "unread", "message", "message"]);
  });
});

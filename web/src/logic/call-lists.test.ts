import { expect, test } from "vitest";
import { callRows, talkMs } from "./call-lists";
import type { DexCallLog } from "../wire/models";

const missed: DexCallLog = {
  id: "1", peer: "fd00::2", label: "Alice", dir: "in", isVideo: false,
  outcome: "missed", startedAt: 1, endedAt: 2,
};
const answered: DexCallLog = {
  id: "2", peer: "fd00::3", label: "Bob", dir: "out", isVideo: true,
  outcome: "answered", startedAt: 3, connectedAt: 4, endedAt: 14,
};

test("call filters and duration ordering follow the phone's log", () => {
  expect(callRows([missed, answered], "missed", "newest", "")).toEqual([missed]);
  expect(callRows([missed, answered], "video", "newest", "")).toEqual([answered]);
  expect(callRows([missed, answered], "all", "longest", "")).toEqual([answered, missed]);
  expect(talkMs(answered)).toBe(10);
});

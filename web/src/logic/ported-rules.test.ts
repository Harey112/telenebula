import { expect, test } from "vitest";
import { canEdit, canReply, composerBlock, mediaOpen, revealStep } from "./chat-rules";
import { contactLabel, contactRows } from "./contact-lists";
import { addressFromCode, newContactProblem, normalizeOverlay } from "./overlay";
import type { DexMessage } from "../wire/models";

const message: DexMessage = {
  id: "m", peer: "fd00::2", dir: "out", body: "hello", ts: 1, status: "delivered", kind: "text",
};

test("chat actions respect the phone's content and contact restrictions", () => {
  expect(composerBlock({ ip: "fd00::2", label: "A", name: "A", isArchived: true }, true)).toBe("archived");
  expect(canEdit(message, "none")).toBe(true);
  expect(canEdit({ ...message, isCovered: true }, "none")).toBe(false);
  expect(canReply({ ...message, isDeleted: true }, "none")).toBe(false);
  expect(mediaOpen({ ...message, kind: "image", att: { name: "photo", mime: "image/png", size: 1, hasFile: true } }))
    .toEqual({ kind: "view", id: "m", name: "photo", isVideo: false });
  expect(revealStep("code")).toBe("on-phone");
});

test("contact rows use the announced name and recent activity", () => {
  const first = { ip: "fd00::2", label: "Nick", name: "Alice", contactLabel: "Ann", lastSeenAt: 1 };
  const second = { ip: "fd00::3", label: "Bob", name: "Bob", lastSeenAt: 2 };
  expect(contactLabel(first)).toBe("Ann");
  expect(contactRows([first, second], "").map((item) => item.ip)).toEqual(["fd00::3", "fd00::2"]);
  expect(contactRows([first, second], "ann")).toEqual([first]);
});

test("overlay QR and address validation match the phone's expected shape", () => {
  expect(normalizeOverlay(" [FD00::2%eth0] ")).toBe("fd00::2");
  expect(addressFromCode('{"name":"Alice","ip":"fd00::2"}')).toBe("fd00::2");
  expect(newContactProblem("fd00::2", "fd00::1", () => "Alice").problem)
    .toBe("Alice is already saved at fd00::2.");
  expect(newContactProblem("invalid", null, () => null).problem).toContain("Nebula IPv6");
  for (const malformed of [":::2", "fd00:::2", "fd00::1::2", "::", "12345::1"]) {
    expect(newContactProblem(malformed, null, () => null).problem).toContain("Nebula IPv6");
  }
});

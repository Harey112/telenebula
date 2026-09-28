import { expect, test } from "vitest";
import {
  askedProfile, baseProfile, effectiveProfile, presenceState, profileDefaults,
  receivedProfile, resetBrowserNotifications,
} from "./effective";

test("browser defaults are independent of phone settings", () => {
  expect(effectiveProfile({ isDeveloperMode: true }).isEnterToSend).toBe(false);
  expect(effectiveProfile({ dexProfile: { isEnterToSend: true } }).isEnterToSend).toBe(true);
  expect(resetBrowserNotifications({ notificationSound: false, sendReadReceipts: false }))
    .toEqual({ notificationSound: true, notificationPreview: true, notificationsEnabled: true, sendReadReceipts: false });
});

test("a pending profile edit survives an older settings snapshot", () => {
  const next = { ...profileDefaults, isEnterToSend: true };
  const edits = askedProfile(next);
  expect(baseProfile(edits, profileDefaults)).toEqual(next);
  expect(receivedProfile(edits, profileDefaults)).toBe(edits);
  expect(receivedProfile(edits, next)).toEqual({ pending: null });
});

test("presence pause uses the phone's timestamp", () => {
  expect(presenceState({ isShared: true, pausedUntil: 200 }, 100))
    .toEqual({ isActive: false, isPaused: true, msUntilChange: 100 });
});

import type { DexProfile, DexSettings } from "../wire/models";

export type EffectiveProfile = Required<DexProfile>;

export const profileDefaults: EffectiveProfile = {
  sendReadReceipts: true,
  sendTypingIndicators: true,
  coverRevealGate: "tap",
  themeMode: "system",
  colorTheme: "sky",
  customAccent: "#7FB7E6",
  chatTextSize: "medium",
  messageDensity: "comfortable",
  isEnterToSend: false,
  notificationsEnabled: true,
  notificationPreview: true,
  notificationSound: true,
};

export function effectiveProfile(settings: DexSettings | null): EffectiveProfile {
  return { ...profileDefaults, ...settings?.dexProfile };
}

export function resetBrowserNotifications(profile: DexProfile): DexProfile {
  return {
    ...profile,
    notificationsEnabled: profileDefaults.notificationsEnabled,
    notificationPreview: profileDefaults.notificationPreview,
    notificationSound: profileDefaults.notificationSound,
  };
}

export interface PresenceState { isActive: boolean; isPaused: boolean; msUntilChange: number | null }

export function presenceState(
  prefs: { isShared?: boolean; pausedUntil?: number }, now: number,
): PresenceState {
  const isShared = prefs.isShared ?? true;
  const pausedUntil = prefs.pausedUntil ?? 0;
  const isPaused = isShared && pausedUntil > now;
  return { isActive: isShared && !isPaused, isPaused, msUntilChange: isPaused ? pausedUntil - now : null };
}

export interface ProfileEdits { pending: EffectiveProfile | null }

export function baseProfile(edits: ProfileEdits, confirmed: DexProfile): EffectiveProfile {
  return edits.pending ?? { ...profileDefaults, ...confirmed };
}

export function askedProfile(next: EffectiveProfile): ProfileEdits {
  return { pending: next };
}

export function receivedProfile(edits: ProfileEdits, confirmed: DexProfile): ProfileEdits {
  const pending = edits.pending;
  if (!pending) return edits;
  const current = { ...profileDefaults, ...confirmed };
  return (Object.keys(profileDefaults) as (keyof EffectiveProfile)[])
    .every((key) => pending[key] === current[key]) ? { pending: null } : edits;
}

export function shownProfile(edits: ProfileEdits, confirmed: DexProfile): EffectiveProfile {
  return baseProfile(edits, confirmed);
}

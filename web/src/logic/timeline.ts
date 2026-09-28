import { dayLabel, isSameDay } from "./format";
import type { DexMessage } from "../wire/models";

export type TimelineItem = { kind: "day"; key: string; label: string } | { kind: "unread"; key: string } | { kind: "message"; key: string; message: DexMessage };

export function timeline(messages: DexMessage[], now = Date.now()): TimelineItem[] {
  const items: TimelineItem[] = [];
  let previous: number | null = null;
  let hasUnreadDivider = false;
  for (const message of messages) {
    if (previous === null || !isSameDay(previous, message.ts)) {
      items.push({ kind: "day", key: `day-${message.id}`, label: dayLabel(message.ts, now) });
    }
    if (!hasUnreadDivider && message.dir === "in" && message.isRead === false) {
      items.push({ kind: "unread", key: `unread-${message.id}` });
      hasUnreadDivider = true;
    }
    items.push({ kind: "message", key: message.id, message });
    previous = message.ts;
  }
  return items;
}

export function reactionCounts(reactions: Record<string, string> | undefined): { emoji: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const emoji of Object.values(reactions ?? {})) counts.set(emoji, (counts.get(emoji) ?? 0) + 1);
  return [...counts].map(([emoji, count]) => ({ emoji, count }));
}

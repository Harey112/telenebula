import type { DexChat } from "../wire/models";

export function chatRows(chats: readonly DexChat[], archived: boolean, openPeer: string | null, search: string): DexChat[] {
  const query = search.trim().toLocaleLowerCase();
  return chats.filter((chat) => Boolean(chat.isArchived) === archived && (chat.lastTs !== undefined || chat.peer === openPeer))
    .filter((chat) => !query || chat.label.toLocaleLowerCase().includes(query) || chat.peer.includes(query))
    .sort((left, right) => Number(Boolean(right.isPinned)) - Number(Boolean(left.isPinned))
      || (right.lastTs ?? 0) - (left.lastTs ?? 0));
}

export function archivedSummary(chats: readonly DexChat[]): { count: number; unread: number } {
  const rows = chats.filter((chat) => chat.isArchived && chat.lastTs !== undefined);
  return { count: rows.length, unread: rows.reduce((total, chat) => total + (chat.unread ?? 0), 0) };
}

export function archivedViewAfterUpdate(
  previous: readonly DexChat[], next: readonly DexChat[], openPeer: string | null, archived: boolean,
): boolean {
  if (!openPeer) return archived;
  const before = previous.find((chat) => chat.peer === openPeer);
  const after = next.find((chat) => chat.peer === openPeer);
  return before && after && Boolean(before.isArchived) !== Boolean(after.isArchived)
    ? Boolean(after.isArchived) : archived;
}

export function archivedViewForOpening(
  peer: string, chats: readonly DexChat[], contactArchived: boolean | undefined, archived: boolean,
): boolean {
  const chat = chats.find((item) => item.peer === peer);
  return chat ? Boolean(chat.isArchived) : contactArchived ?? archived;
}

export function chatEmptyText(archived: boolean, search: string, hasArchived: boolean): string {
  const query = search.trim();
  if (query && archived) return `No archived chats match “${query}”.`;
  if (query) return `No chats match “${query}”.`;
  if (archived) return "No archived chats.";
  return hasArchived ? "Every chat is archived." : "No chats yet. Start one with New message.";
}

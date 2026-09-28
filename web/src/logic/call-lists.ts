import type { DexCallLog } from "../wire/models";

export type CallFilter = "all" | "missed" | "in" | "out" | "video";
export type CallSort = "newest" | "oldest" | "name" | "longest";

export function callMatches(log: DexCallLog, filter: CallFilter): boolean {
  switch (filter) {
    case "all": return true;
    case "missed": return log.dir === "in" && (log.outcome === "missed" || log.outcome === "declined");
    case "in": return log.dir === "in";
    case "out": return log.dir === "out";
    case "video": return log.isVideo;
  }
}

export function talkMs(log: DexCallLog): number {
  return log.connectedAt === undefined ? 0 : Math.max(0, log.endedAt - log.connectedAt);
}

export function callRows(logs: readonly DexCallLog[], filter: CallFilter, sort: CallSort, search: string): DexCallLog[] {
  const query = search.trim().toLocaleLowerCase();
  const shown = logs.filter((log) => callMatches(log, filter)
    && (!query || log.label.toLocaleLowerCase().includes(query) || log.peer.includes(query)));
  return shown.sort((left, right) => {
    switch (sort) {
      case "newest": return right.startedAt - left.startedAt;
      case "oldest": return left.startedAt - right.startedAt;
      case "name": return left.label.localeCompare(right.label) || right.startedAt - left.startedAt;
      case "longest": return talkMs(right) - talkMs(left) || right.startedAt - left.startedAt;
    }
  });
}

export function callEmptyText(hasCalls: boolean): string {
  return hasCalls ? "No calls match." : "No calls yet. Start one from a chat or a contact.";
}

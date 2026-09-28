import type { DexCallPhase, DexContact, DexMessage, DexRevealGate } from "../wire/models";

export type ComposerBlock = "none" | "archived" | "blocked" | "tunnel-off";
export type RevealStep = "reveal" | "confirm" | "on-phone";
export type MediaOpen = { kind: "view"; id: string; name: string; isVideo: boolean }
  | { kind: "download" } | { kind: "unavailable" };

export function composerBlock(contact: DexContact | null, isTunnelOn: boolean | null): ComposerBlock {
  if (contact?.isArchived) return "archived";
  if (contact?.isBlocked) return "blocked";
  return isTunnelOn === false ? "tunnel-off" : "none";
}

export function canCall(contact: DexContact | null, isTunnelOn: boolean | null, phase: DexCallPhase): boolean {
  return composerBlock(contact, isTunnelOn) === "none" && (phase === "idle" || phase === "ended");
}

function isContentless(message: DexMessage): boolean {
  return Boolean(message.isDeleted) || message.status === "declined" || message.status === "cancelled";
}

export function canReply(message: DexMessage, block: ComposerBlock): boolean {
  return !isContentless(message) && block === "none";
}

export function canEdit(message: DexMessage, block: ComposerBlock): boolean {
  return message.dir === "out" && !message.isDeleted && message.kind === "text"
    && !message.isCovered && block === "none";
}

export function mediaOpen(message: DexMessage): MediaOpen {
  const att = message.att;
  if (!att || !att.hasFile || message.isDeleted) return { kind: "unavailable" };
  if (message.kind === "image" || message.kind === "video") {
    return { kind: "view", id: message.id, name: att.name, isVideo: message.kind === "video" };
  }
  return { kind: "download" };
}

export function revealStep(gate: DexRevealGate): RevealStep {
  if (gate === "tap") return "reveal";
  return gate === "ask" ? "confirm" : "on-phone";
}

export function canCopy(message: DexMessage): boolean {
  return !isContentless(message) && !message.isCovered && message.body.length > 0;
}

export function canDeleteForEveryone(message: DexMessage): boolean {
  return message.dir === "out" && !message.isDeleted && message.send === undefined && message.status !== "pending";
}

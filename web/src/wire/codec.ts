import type { ClientFrame, ServerFrame } from "./frames";

type RecordValue = Record<string, unknown>;

function isRecord(value: unknown): value is RecordValue {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isString(value: unknown): value is string { return typeof value === "string"; }
function isNumber(value: unknown): value is number { return typeof value === "number" && Number.isFinite(value); }
function isBoolean(value: unknown): value is boolean { return typeof value === "boolean"; }
function isArray(value: unknown): value is unknown[] { return Array.isArray(value); }
function has(value: RecordValue, field: string, check: (candidate: unknown) => boolean): boolean {
  return check(value[field]);
}
function optionalString(value: RecordValue, field: string): boolean {
  return value[field] === undefined || isString(value[field]);
}

function isIdentity(value: unknown): boolean {
  return isRecord(value) && has(value, "name", isString) && has(value, "ip", isString);
}

function isMessage(value: unknown): boolean {
  return isRecord(value) && has(value, "id", isString) && has(value, "peer", isString)
    && has(value, "body", isString) && has(value, "ts", isNumber)
    && has(value, "dir", isString) && has(value, "status", isString) && has(value, "kind", isString);
}

function isContact(value: unknown): boolean {
  return isRecord(value) && has(value, "ip", isString) && has(value, "label", isString)
    && has(value, "name", isString);
}

function isChat(value: unknown): boolean {
  return isRecord(value) && has(value, "peer", isString) && has(value, "label", isString);
}

function isList(value: unknown, check: (candidate: unknown) => boolean): boolean {
  return isArray(value) && value.every(check);
}

function hasList(frame: RecordValue, key: string, check: (candidate: unknown) => boolean): boolean {
  return has(frame, key, (value) => isList(value, check));
}

function isChatView(value: unknown): boolean {
  return isRecord(value) && has(value, "peer", isString) && has(value, "contact", isContact)
    && hasList(value, "messages", isMessage) && has(value, "hasMore", isBoolean);
}

function isFrame(value: unknown): value is ServerFrame {
  if (!isRecord(value) || !isString(value.t)) return false;
  switch (value.t) {
    case "pong": return true;
    case "hello": return has(value, "me", isIdentity) && has(value, "clientId", isString) && has(value, "freeBytes", isNumber);
    case "chats": return hasList(value, "items", isChat);
    case "contacts": return hasList(value, "items", isContact);
    case "chat": return has(value, "view", isChatView);
    case "chat_more": return has(value, "peer", isString) && hasList(value, "messages", isMessage) && has(value, "hasMore", isBoolean);
    case "presence": return has(value, "items", isRecord);
    case "typing": return hasList(value, "peers", isString);
    case "queues": return hasList(value, "items", (item) => isRecord(item) && has(item, "peer", isString));
    case "tunnel": return has(value, "isOn", isBoolean);
    case "notice": return has(value, "level", isString) && has(value, "message", isString);
    case "error": return has(value, "message", isString) && optionalString(value, "requestId");
    case "call_state": return has(value, "state", isRecord);
    case "call_media": return has(value, "callId", isString) && has(value, "role", isString)
      && has(value, "video", isBoolean) && hasList(value, "iceServers", isRecord);
    case "call_sdp": return has(value, "callId", isString) && has(value, "sdp", isString) && has(value, "sdpType", isString);
    case "call_ice": return has(value, "callId", isString);
    case "call_release": return has(value, "callId", isString) && has(value, "reason", isString);
    case "settings": return has(value, "settings", isRecord);
    case "account": return has(value, "account", isRecord);
    case "network": return has(value, "network", isRecord);
    case "storage": return has(value, "storage", isRecord);
    case "diagnostics": return has(value, "diagnostics", isRecord);
    case "updates": return has(value, "updates", isRecord);
    case "call_logs": return hasList(value, "items", (item) => isRecord(item) && has(item, "id", isString));
    case "contact_detail": return has(value, "detail", (item) => isRecord(item) && has(item, "contact", isContact));
    case "chat_media": return has(value, "peer", isString) && hasList(value, "items", isMessage);
    case "chat_links": return has(value, "peer", isString) && hasList(value, "items", (item) => isRecord(item) && has(item, "messageId", isString));
    case "ping_result": return has(value, "result", (item) => isRecord(item) && has(item, "peer", isString));
    case "search_results": return has(value, "peer", isString) && hasList(value, "items", isMessage);
    case "done": return has(value, "what", isString) && optionalString(value, "requestId");
    default: return false;
  }
}

export function decodeFrame(text: string): ServerFrame | null {
  try {
    const value: unknown = JSON.parse(text);
    return isFrame(value) ? value : null;
  } catch {
    return null;
  }
}

export function encodeFrame(frame: ClientFrame): string {
  return JSON.stringify(frame);
}

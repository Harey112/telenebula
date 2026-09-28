import type { DexIdentity } from "../wire/models";

export type LoginResult =
  | { kind: "ok" }
  | { kind: "wrong" }
  | { kind: "locked" }
  | { kind: "client-limit" }
  | { kind: "failed"; message: string };

export type SessionResult =
  | { kind: "active"; me: DexIdentity }
  | { kind: "none" }
  | { kind: "failed"; message: string };

export interface UploadRequest {
  peer: string;
  name: string;
  mime: string;
  body: Blob;
  replyTo?: string;
  isCovered: boolean;
  isVoice: boolean;
  durationMs?: number;
  width?: number;
  height?: number;
}

export type UploadOutcome = { kind: "done" } | { kind: "cancelled" } | { kind: "failed"; message: string };
export interface EmojiGroup { title: string; emojis: string[] }

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message ? error.message : "The phone did not answer";
}

async function responseError(response: Response): Promise<string> {
  try {
    const value: unknown = await response.json();
    if (isRecord(value) && typeof value.error === "string") return value.error;
  } catch {
    return `The phone answered ${response.status}`;
  }
  return `The phone answered ${response.status}`;
}

const requestInit = { credentials: "same-origin", cache: "no-store" } as const;

export async function session(): Promise<SessionResult> {
  try {
    const response = await fetch("/api/session", { ...requestInit, method: "GET" });
    if (response.status === 401) return { kind: "none" };
    if (response.status !== 200) return { kind: "failed", message: await responseError(response) };
    const body: unknown = await response.json();
    if (!isRecord(body) || typeof body.name !== "string" || typeof body.ip !== "string") {
      return { kind: "failed", message: "The phone sent an invalid session" };
    }
    return { kind: "active", me: { name: body.name, ip: body.ip } };
  } catch (error) {
    return { kind: "failed", message: errorMessage(error) };
  }
}

export async function login(username: string, password: string): Promise<LoginResult> {
  try {
    const response = await fetch("/api/login", {
      ...requestInit, method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
    switch (response.status) {
      case 200:
      case 204: return { kind: "ok" };
      case 401: return { kind: "wrong" };
      case 429: return { kind: "locked" };
      case 503: return { kind: "client-limit" };
      default: return { kind: "failed", message: await responseError(response) };
    }
  } catch (error) {
    return { kind: "failed", message: errorMessage(error) };
  }
}

export async function logout(): Promise<boolean> {
  try {
    return (await fetch("/api/logout", { ...requestInit, method: "POST" })).ok;
  } catch {
    return false;
  }
}

export async function emojiCatalog(): Promise<EmojiGroup[]> {
  const response = await fetch("/emoji_catalog.json", { ...requestInit, method: "GET" });
  if (!response.ok) throw new Error(await responseError(response));
  const value: unknown = await response.json();
  if (!Array.isArray(value) || !value.every((group) => isRecord(group)
    && typeof group.title === "string" && Array.isArray(group.emojis)
    && group.emojis.every((emoji: unknown) => typeof emoji === "string"))) {
    throw new Error("The phone sent an invalid emoji catalogue");
  }
  return value as EmojiGroup[];
}

export function attachmentUrl(messageId: string): string {
  return `/a/${encodeURIComponent(messageId)}`;
}

export function uploadUrl(request: UploadRequest): string {
  const url = new URL("/a", window.location.origin);
  url.searchParams.set("peer", request.peer);
  url.searchParams.set("name", request.name);
  url.searchParams.set("mime", request.mime);
  if (request.replyTo !== undefined) url.searchParams.set("reply", request.replyTo);
  if (request.isCovered) url.searchParams.set("cover", "1");
  if (request.isVoice) url.searchParams.set("voice", "1");
  if (request.durationMs !== undefined) url.searchParams.set("durationMs", String(request.durationMs));
  if (request.width !== undefined) url.searchParams.set("width", String(request.width));
  if (request.height !== undefined) url.searchParams.set("height", String(request.height));
  return url.pathname + url.search;
}

export function upload(
  request: UploadRequest,
  onStart: (abort: () => void) => void,
  onProgress: (percent: number) => void,
): Promise<UploadOutcome> {
  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest();
    let settled = false;
    const settle = (outcome: UploadOutcome): void => {
      if (settled) return;
      settled = true;
      resolve(outcome);
    };
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) {
        onProgress(Math.min(100, Math.max(0, Math.floor(event.loaded / event.total * 100))));
      }
    };
    xhr.onload = () => {
      if (xhr.status === 200 || xhr.status === 201) {
        settle({ kind: "done" });
      } else if (xhr.status === 401) {
        settle({ kind: "failed", message: "Not logged in" });
      } else if (xhr.status === 413) {
        settle({ kind: "failed", message: "That file is too large for Dex" });
      } else {
        let message = `The phone answered ${xhr.status}`;
        try {
          const value: unknown = JSON.parse(xhr.responseText);
          if (isRecord(value) && typeof value.error === "string") message = value.error;
        } catch {
          message = `The phone answered ${xhr.status}`;
        }
        settle({ kind: "failed", message });
      }
    };
    xhr.onerror = () => settle({ kind: "failed", message: "Upload failed" });
    xhr.onabort = () => settle({ kind: "cancelled" });
    xhr.ontimeout = () => settle({ kind: "failed", message: "Upload timed out" });
    try {
      xhr.open("POST", uploadUrl(request));
      xhr.setRequestHeader("Content-Type", request.mime || "application/octet-stream");
      onStart(() => xhr.abort());
      xhr.send(request.body);
    } catch (error) {
      settle({ kind: "failed", message: errorMessage(error) });
    }
  });
}

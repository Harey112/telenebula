import type { Page, WebSocketRoute } from "@playwright/test";
import type { ServerFrame } from "../src/wire/frames";
import type { DexChat, DexContact, DexIdentity, DexSettings } from "../src/wire/models";

export interface MockPhoneOptions {
  active?: boolean;
  me?: DexIdentity;
  chats?: DexChat[];
  contacts?: DexContact[];
  settings?: DexSettings;
}

export interface RecordedFrame { t: string; [key: string]: unknown }

export async function installMockPhone(page: Page, options: MockPhoneOptions = {}) {
  let active = options.active ?? true;
  let socket: WebSocketRoute | null = null;
  const frames: RecordedFrame[] = [];
  const me = options.me ?? { name: "TeleNebula", ip: "fd00::1" };

  await page.route("**/api/session", async (route) => {
    if (!active) {
      await route.fulfill({ status: 401 });
      return;
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(me) });
  });

  await page.route("**/api/login", async (route) => {
    const body: unknown = route.request().postDataJSON();
    if (typeof body === "object" && body !== null && "username" in body && "password" in body
      && body.username === "dex" && body.password === "password") {
      active = true;
      await route.fulfill({ status: 204 });
    } else {
      await route.fulfill({ status: 401 });
    }
  });

  await page.route("**/api/logout", async (route) => {
    active = false;
    await route.fulfill({ status: 204 });
  });

  await page.route("**/emoji_catalog.json", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ title: "Smileys", emojis: ["😀", "👍", "❤️"] }]) });
  });

  await page.routeWebSocket("**/ws", (route) => {
    if (!active) {
      void route.close({ code: 1008, reason: "unauthorized" });
      return;
    }
    socket = route;
    for (const frame of [
      { t: "hello", me, clientId: "mock-client", freeBytes: 1_000_000 },
      { t: "chats", items: options.chats ?? [] },
      { t: "contacts", items: options.contacts ?? [] },
      { t: "settings", settings: options.settings ?? {} },
      { t: "call_state", state: {} },
      { t: "tunnel", isOn: true },
    ] satisfies ServerFrame[]) route.send(JSON.stringify(frame));
    route.onMessage((message) => {
      if (typeof message !== "string") return;
      let value: unknown;
      try { value = JSON.parse(message); } catch { return; }
      if (typeof value !== "object" || value === null || !("t" in value) || typeof value.t !== "string") return;
      frames.push(value as RecordedFrame);
      if (value.t === "ping") route.send('{"t":"pong"}');
    });
  });

  return {
    frames,
    send(frame: ServerFrame): void { socket?.send(JSON.stringify(frame)); },
    setActive(value: boolean): void { active = value; },
  };
}

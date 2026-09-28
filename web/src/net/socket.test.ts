import { afterEach, expect, test, vi } from "vitest";
import { DexSocket } from "./socket";

const instances: FakeSocket[] = [];

class FakeSocket {
  static readonly OPEN = 1;
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((event: MessageEvent<unknown>) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  send = vi.fn();
  constructor(readonly url: string) { instances.push(this); }
  open(): void { this.readyState = 1; this.onopen?.(); }
  close(): void { this.readyState = 3; }
  finish(code: number, reason = ""): void {
    this.readyState = 3;
    this.onclose?.(new CloseEvent("close", { code, reason }));
  }
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  instances.length = 0;
});

test("reconnects after a limit close and stops after an unauthorized close", () => {
  vi.useFakeTimers();
  vi.stubGlobal("WebSocket", FakeSocket);
  const onStatus = vi.fn();
  const socket = new DexSocket({ onFrame: vi.fn(), onOpen: vi.fn(), onStatus, onError: vi.fn() });
  socket.start();
  const first = instances[0];
  if (!first) throw new Error("socket did not open");
  expect(first.url).toContain("/ws");
  first.open();
  expect(socket.send({ t: "ping" })).toBe(true);
  expect(first.send).toHaveBeenCalledWith('{"t":"ping"}');
  first.finish(1013, "client limit");
  expect(onStatus).toHaveBeenCalledWith("limit");
  vi.advanceTimersByTime(1_000);
  const second = instances[1];
  if (!second) throw new Error("socket did not reconnect");
  second.finish(1008, "unauthorized");
  expect(onStatus).toHaveBeenCalledWith("unauthorized");
  vi.advanceTimersByTime(15_000);
  expect(instances).toHaveLength(2);
  socket.stop();
});

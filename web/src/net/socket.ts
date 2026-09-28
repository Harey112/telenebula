import { decodeFrame, encodeFrame } from "../wire/codec";
import type { ClientFrame, ServerFrame } from "../wire/frames";

export type SocketStatus = "connecting" | "connected" | "reconnecting" | "unauthorized" | "limit";

export interface SocketCallbacks {
  onFrame: (frame: ServerFrame) => void;
  onStatus: (status: SocketStatus) => void;
  onOpen: () => void;
  onError: (message: string) => void;
}

const backoffMs = [1_000, 2_000, 4_000, 8_000, 15_000] as const;
const pingMs = 25_000;

export class DexSocket {
  private socket: WebSocket | undefined;
  private attempt = 0;
  private reconnectTimer: number | undefined;
  private pingTimer: number | undefined;
  private stopped = true;
  private status: SocketStatus = "connecting";

  constructor(private readonly callbacks: SocketCallbacks) {}

  get isConnected(): boolean {
    return this.socket?.readyState === WebSocket.OPEN;
  }

  get failedAttempts(): number {
    return this.attempt;
  }

  start(): void {
    this.stop();
    this.stopped = false;
    this.attempt = 0;
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    this.clearReconnect();
    this.clearPing();
    const socket = this.socket;
    this.socket = undefined;
    if (socket) {
      socket.onclose = null;
      socket.close();
    }
  }

  send(frame: ClientFrame): boolean {
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) return false;
    try {
      socket.send(encodeFrame(frame));
      return true;
    } catch {
      this.callbacks.onError("Dex connection could not send a request");
      return false;
    }
  }

  private connect(): void {
    if (this.stopped) return;
    const scheme = window.location.protocol === "https:" ? "wss:" : "ws:";
    let socket: WebSocket;
    try {
      socket = new WebSocket(`${scheme}//${window.location.host}/ws`);
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.socket = socket;
    this.setStatus(this.attempt === 0 ? "connecting" : "reconnecting");
    socket.onopen = () => {
      if (this.socket !== socket || this.stopped) return;
      this.attempt = 0;
      this.setStatus("connected");
      this.clearPing();
      this.pingTimer = window.setInterval(() => this.send({ t: "ping" }), pingMs);
      try {
        this.callbacks.onOpen();
      } catch {
        this.callbacks.onError("Dex could not restore the open view");
      }
    };
    socket.onmessage = (event: MessageEvent<unknown>) => {
      if (this.socket !== socket || this.stopped) return;
      if (typeof event.data !== "string") {
        this.callbacks.onError("Dex sent a non-text frame");
        return;
      }
      const frame = decodeFrame(event.data);
      if (!frame) {
        this.callbacks.onError("Dex sent an invalid frame");
        return;
      }
      try {
        this.callbacks.onFrame(frame);
      } catch {
        this.callbacks.onError("Dex could not apply a phone update");
      }
    };
    socket.onclose = (event: CloseEvent) => {
      if (this.socket !== socket) return;
      this.socket = undefined;
      this.clearPing();
      if (this.stopped) return;
      if (event.code === 1008 || event.reason === "unauthorized") {
        this.setStatus("unauthorized");
      } else if (event.code === 1013 && event.reason.toLowerCase().includes("limit")) {
        this.setStatus("limit");
        this.scheduleReconnect();
      } else {
        this.scheduleReconnect();
      }
    };
  }

  private scheduleReconnect(): void {
    if (this.stopped || this.reconnectTimer !== undefined) return;
    if (this.status !== "limit") this.setStatus("reconnecting");
    const delay = backoffMs[Math.min(this.attempt, backoffMs.length - 1)] ?? 15_000;
    this.attempt += 1;
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = undefined;
      this.connect();
    }, delay);
  }

  private clearReconnect(): void {
    if (this.reconnectTimer !== undefined) window.clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined;
  }

  private clearPing(): void {
    if (this.pingTimer !== undefined) window.clearInterval(this.pingTimer);
    this.pingTimer = undefined;
  }

  private setStatus(next: SocketStatus): void {
    if (this.status === next) return;
    this.status = next;
    this.callbacks.onStatus(next);
  }
}

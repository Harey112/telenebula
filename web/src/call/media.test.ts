import { afterEach, expect, it, vi } from "vitest";
import { CallMedia, type CallMediaPort } from "./media";

afterEach(() => vi.unstubAllGlobals());

it("waits for microphone tracks and the local offer before applying an early answer", async () => {
  let releaseMicrophone: ((stream: MediaStream) => void) | undefined;
  const microphone = new Promise<MediaStream>((resolve) => { releaseMicrophone = resolve; });
  const events: string[] = [];
  const track = { kind: "audio", stop: vi.fn() } as unknown as MediaStreamTrack;
  const stream = { getTracks: () => [track] } as unknown as MediaStream;
  class Peer {
    localDescription: RTCSessionDescriptionInit | null = null;
    signalingState = "stable";
    onicecandidate: ((event: RTCPeerConnectionIceEvent) => void) | null = null;
    ontrack: ((event: RTCTrackEvent) => void) | null = null;
    onconnectionstatechange: (() => void) | null = null;
    onnegotiationneeded: (() => void) | null = null;
    addTrack() { events.push("track"); return {} as RTCRtpSender; }
    addTransceiver() { return { sender: {} as RTCRtpSender }; }
    async createOffer() { events.push("offer"); return { type: "offer" as const, sdp: "local" }; }
    async setLocalDescription(description: RTCSessionDescriptionInit) { this.localDescription = description; events.push("local"); }
    async setRemoteDescription() { events.push("remote"); }
    async addIceCandidate() {}
    close() {}
  }
  vi.stubGlobal("RTCPeerConnection", Peer);
  Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: { getUserMedia: () => microphone } });
  const port: CallMediaPort = {
    onSdp: () => events.push("send"), onIce: () => {}, onConnected: () => {},
    onFailed: (callId, reason) => { throw new Error(`${callId}: ${reason}`); },
    onWarning: () => {}, onLocalStream: () => {}, onRemoteStream: () => {},
  };
  const media = new CallMedia(port);
  const opened = media.open({ callId: "one", isOfferer: true, video: false, iceServers: [] });
  await media.remoteSdp("one", "answer", "answer");
  expect(events).toEqual([]);
  releaseMicrophone?.(stream);
  await opened;
  await vi.waitFor(() => expect(events).toEqual(["track", "offer", "local", "send", "remote"]));
  media.close();
  expect(track.stop).toHaveBeenCalledOnce();
});

import type { DexIceCandidate, DexIceServer } from "../wire/models";

export interface CallMediaPort {
  onSdp: (callId: string, sdp: string, type: string) => void;
  onIce: (callId: string, candidate?: DexIceCandidate) => void;
  onConnected: (callId: string) => void;
  onFailed: (callId: string, reason: string) => void;
  onWarning: (reason: string) => void;
  onLocalStream: (stream: MediaStream | null) => void;
  onRemoteStream: (stream: MediaStream | null) => void;
}

export class CallMedia {
  private pc: RTCPeerConnection | null = null;
  private localStream: MediaStream | null = null;
  private callId: string | null = null;
  private candidates: (DexIceCandidate | undefined)[] = [];
  private hasRemoteDescription = false;
  private hasReportedConnected = false;
  private isNegotiating = false;
  private isReadyForRemote = false;
  private isApplyingRemote = false;
  private remoteDescriptions: { sdp: string; type: string }[] = [];
  private videoSender: RTCRtpSender | null = null;
  private generation = 0;
  isMuted = false;
  isCameraOn = false;

  constructor(private readonly port: CallMediaPort) {}

  get isSupported(): boolean { return typeof RTCPeerConnection !== "undefined" && !!navigator.mediaDevices?.getUserMedia; }
  get isActive(): boolean { return this.pc !== null; }
  get currentCallId(): string | null { return this.callId; }

  async open(input: { callId: string; isOfferer: boolean; video: boolean; iceServers: DexIceServer[];
    remoteSdp?: string; remoteSdpType?: string; isRestart?: boolean }): Promise<void> {
    if (!this.isSupported) { this.port.onFailed(input.callId, "Calls need a secure origin and a modern browser"); return; }
    if (input.isRestart && this.pc && this.callId === input.callId) { await this.restart(); return; }
    this.close();
    const generation = this.generation;
    this.callId = input.callId;
    try {
      const pc = new RTCPeerConnection({ iceServers: input.iceServers, iceTransportPolicy: "relay" });
      this.pc = pc;
      this.wire(pc, input.callId);
      let stream: MediaStream;
      try { stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: input.video ? { facingMode: "user" } : false }); }
      catch {
        if (this.generation !== generation || this.pc !== pc) return;
        this.port.onFailed(input.callId, input.video ? "Camera or microphone is not available" : "Microphone is not available");
        this.close();
        return;
      }
      if (this.generation !== generation || this.pc !== pc) { stream.getTracks().forEach((track) => track.stop()); return; }
      this.localStream = stream;
      this.isCameraOn = input.video;
      this.isMuted = false;
      for (const track of stream.getTracks()) {
        const sender = pc.addTrack(track, stream);
        if (track.kind === "video") this.videoSender = sender;
      }
      if (!input.video) this.videoSender = pc.addTransceiver("video", { direction: "recvonly" }).sender;
      this.port.onLocalStream(stream);
      if (input.isOfferer) {
        await pc.setLocalDescription(await pc.createOffer());
        if (this.pc !== pc || this.generation !== generation) return;
        this.sendLocalDescription(input.callId, pc);
        this.isReadyForRemote = true;
      } else if (input.remoteSdp) {
        if (this.pc !== pc || this.generation !== generation) return;
        this.isReadyForRemote = true;
        this.remoteDescriptions.unshift({ sdp: input.remoteSdp, type: input.remoteSdpType ?? "offer" });
      } else {
        if (this.generation !== generation || this.pc !== pc) return;
        this.port.onFailed(input.callId, "The call arrived without an offer");
        this.close();
        return;
      }
      void this.applyQueuedRemote();
    } catch (error) {
      if (this.generation !== generation || this.callId !== input.callId) return;
      this.port.onFailed(input.callId, error instanceof Error ? error.message : "Could not set up the call");
      this.close();
    }
  }

  private wire(pc: RTCPeerConnection, callId: string): void {
    pc.onicecandidate = (event) => {
      const candidate = event.candidate;
      this.port.onIce(callId, candidate && candidate.candidate ? {
        candidate: candidate.candidate,
        ...(candidate.sdpMid ? { sdpMid: candidate.sdpMid } : {}),
        ...(candidate.sdpMLineIndex !== null ? { sdpMLineIndex: candidate.sdpMLineIndex } : {}),
      } : undefined);
    };
    pc.ontrack = (event) => this.port.onRemoteStream(event.streams[0] ?? new MediaStream([event.track]));
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === "connected" && !this.hasReportedConnected) {
        this.hasReportedConnected = true;
        this.port.onConnected(callId);
      } else if (pc.connectionState === "failed") this.port.onFailed(callId, "ICE failed");
    };
    pc.onnegotiationneeded = () => { if (this.hasRemoteDescription && !this.isNegotiating) void this.renegotiate(callId); };
  }

  private sendLocalDescription(callId: string, pc: RTCPeerConnection): void {
    if (this.pc !== pc || this.callId !== callId) return;
    const description = pc.localDescription;
    if (description?.sdp) this.port.onSdp(callId, description.sdp, description.type);
  }

  private async renegotiate(callId: string): Promise<void> {
    const pc = this.pc;
    if (!pc || pc.signalingState !== "stable") return;
    this.isNegotiating = true;
    try {
      await pc.setLocalDescription(await pc.createOffer());
      if (this.pc === pc) this.sendLocalDescription(callId, pc);
    } catch { this.port.onWarning("Could not update the call connection."); }
    finally { this.isNegotiating = false; }
  }

  private async restart(): Promise<void> {
    const pc = this.pc;
    const callId = this.callId;
    if (!pc || !callId) return;
    this.hasReportedConnected = false;
    try {
      pc.restartIce();
      await pc.setLocalDescription(await pc.createOffer({ iceRestart: true }));
      this.sendLocalDescription(callId, pc);
    } catch { this.port.onFailed(callId, "Could not restart the connection"); }
  }

  async remoteSdp(callId: string, sdp: string, type: string): Promise<void> {
    if (this.callId !== callId) return;
    if (this.remoteDescriptions.length >= 4) {
      this.port.onFailed(callId, "Too many call descriptions arrived.");
      return;
    }
    this.remoteDescriptions.push({ sdp, type });
    await this.applyQueuedRemote();
  }

  private async applyQueuedRemote(): Promise<void> {
    if (!this.isReadyForRemote || this.isApplyingRemote || !this.pc) return;
    this.isApplyingRemote = true;
    const pc = this.pc;
    const callId = this.callId;
    try {
      while (this.pc === pc && this.remoteDescriptions.length > 0) {
        const next = this.remoteDescriptions.shift();
        if (!next) break;
        await this.applyRemote(next.sdp, next.type);
      }
    } catch (error) {
      if (this.pc === pc && callId) this.port.onFailed(callId, `The peer's description was refused: ${error instanceof Error ? error.message : "unknown error"}`);
    } finally {
      if (this.pc === pc) this.isApplyingRemote = false;
    }
  }

  private async applyRemote(sdp: string, type: string): Promise<void> {
    const pc = this.pc;
    const callId = this.callId;
    if (!pc || !callId) return;
    if (type === "offer" && pc.signalingState === "have-local-offer") await pc.setLocalDescription({ type: "rollback" });
    await pc.setRemoteDescription({ type: type as RTCSdpType, sdp });
    if (this.pc !== pc) return;
    this.hasRemoteDescription = true;
    for (const candidate of this.candidates) await this.addCandidate(pc, candidate);
    this.candidates = [];
    if (type === "offer") {
      await pc.setLocalDescription(await pc.createAnswer());
      this.sendLocalDescription(callId, pc);
    }
  }

  remoteIce(callId: string, candidate?: DexIceCandidate): void {
    if (this.callId !== callId || !this.pc) return;
    if (!this.hasRemoteDescription) {
      if (this.candidates.length < 64) this.candidates.push(candidate);
      return;
    }
    void this.addCandidate(this.pc, candidate);
  }

  private async addCandidate(pc: RTCPeerConnection, candidate?: DexIceCandidate): Promise<void> {
    try { await pc.addIceCandidate(candidate ? { candidate: candidate.candidate,
      ...(candidate.sdpMid ? { sdpMid: candidate.sdpMid } : {}),
      ...(candidate.sdpMLineIndex !== undefined ? { sdpMLineIndex: candidate.sdpMLineIndex } : {}) } : null); }
    catch { this.port.onWarning("A call network candidate was refused."); }
  }

  setMuted(muted: boolean): void {
    this.isMuted = muted;
    this.localStream?.getAudioTracks().forEach((track) => { track.enabled = !muted; });
  }

  async setCamera(on: boolean): Promise<boolean> {
    const pc = this.pc;
    const stream = this.localStream;
    if (!pc || !stream) return false;
    let acquiredCamera: MediaStream | null = null;
    try {
      if (!on) {
        await this.videoSender?.replaceTrack(null);
        if (this.pc !== pc) return false;
        for (const track of stream.getVideoTracks()) { track.stop(); stream.removeTrack(track); }
        this.isCameraOn = false;
        this.port.onLocalStream(stream);
        return true;
      }
      acquiredCamera = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "user" } });
      const track = acquiredCamera.getVideoTracks()[0];
      if (!track || this.pc !== pc) return false;
      if (this.videoSender) {
        await this.videoSender.replaceTrack(track);
        if (this.pc !== pc) return false;
        for (const transceiver of pc.getTransceivers()) if (transceiver.sender === this.videoSender) transceiver.direction = "sendrecv";
      } else this.videoSender = pc.addTrack(track, stream);
      stream.addTrack(track);
      this.isCameraOn = true;
      this.port.onLocalStream(stream);
      acquiredCamera = null;
      return true;
    } catch { return false; }
    finally { acquiredCamera?.getTracks().forEach((track) => track.stop()); }
  }

  close(): void {
    this.generation += 1;
    const pc = this.pc;
    this.pc = null;
    this.callId = null;
    this.hasRemoteDescription = false;
    this.hasReportedConnected = false;
    this.isNegotiating = false;
    this.isReadyForRemote = false;
    this.isApplyingRemote = false;
    this.remoteDescriptions = [];
    this.videoSender = null;
    this.candidates = [];
    this.localStream?.getTracks().forEach((track) => track.stop());
    this.localStream = null;
    this.isCameraOn = false;
    this.isMuted = false;
    if (pc) {
      pc.onicecandidate = null;
      pc.ontrack = null;
      pc.onconnectionstatechange = null;
      pc.onnegotiationneeded = null;
      pc.close();
    }
    this.port.onLocalStream(null);
    this.port.onRemoteStream(null);
  }
}

import { createEffect, createSignal, onCleanup, Show } from "solid-js";
import { duration } from "../../logic/format";
import { useDex } from "../../store/context";
import { Ringer } from "../../call/ringer";
import { Icon } from "../icons.gen";
import Dialog from "../kit/Dialog";
import "./CallOverlay.css";

export default function CallOverlay() {
  const runtime = useDex();
  let remoteVideo: HTMLVideoElement | undefined;
  let localVideo: HTMLVideoElement | undefined;
  const [now, setNow] = createSignal(Date.now());
  const [needsPlay, setNeedsPlay] = createSignal(false);
  const ringer = new Ringer();
  createEffect(() => { if (runtime.state.call.phase === "incoming") ringer.start(); else ringer.stop(); });
  onCleanup(() => ringer.stop());
  const isLive = () => runtime.state.call.phase !== undefined && runtime.state.call.phase !== "idle" && runtime.state.call.phase !== "ended" && runtime.state.call.phase !== "incoming";
  const isMine = () => isLive() && runtime.state.call.seat === "dex" && runtime.state.call.seatClientId === runtime.state.clientId;
  const isMirror = () => isLive() && !isMine() && (runtime.state.call.seat === "phone" || runtime.state.call.seat === "dex");
  const peerName = () => runtime.state.call.peer?.name || runtime.state.call.peer?.ip || "Unknown caller";
  const callStatus = () => {
    const call = runtime.state.call;
    if (call.movingTo) return "Moving to phone…";
    if (call.phase === "contacting") return "Contacting…";
    if (call.phase === "ringing") return "Ringing…";
    if (call.phase === "connecting") return "Connecting…";
    if (call.phase === "active") return duration(now() - (call.startedAt ?? now()));
    return "";
  };

  createEffect(() => {
    if (runtime.state.call.phase !== "active") return;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    onCleanup(() => window.clearInterval(timer));
  });
  createEffect(() => {
    const stream = runtime.state.callRemoteStream;
    if (!remoteVideo) return;
    remoteVideo.srcObject = stream;
    if (stream) void remoteVideo.play().then(() => setNeedsPlay(false)).catch(() => setNeedsPlay(true));
  });
  createEffect(() => {
    const stream = runtime.state.callLocalStream;
    if (!localVideo) return;
    localVideo.srcObject = stream;
    if (stream) void localVideo.play().catch(() => undefined);
  });

  return <>
    <Show when={runtime.state.call.phase === "incoming"}>
      <Dialog title="Incoming call" onClose={() => runtime.rejectCall()} actions={<>
        <button type="button" class="dex-call-danger" onClick={() => runtime.rejectCall()}><Icon name="call_end" size={18} />Decline</button>
        <button type="button" data-primary="true" onClick={() => runtime.acceptCall()}><Icon name="call" size={18} />Accept</button>
      </>}><div class="dex-incoming-call"><div class="dex-call-avatar"><Icon name="call" size={28} /></div>
        <strong>{peerName()}</strong><span>{runtime.state.call.video ? "Incoming video call" : "Incoming voice call"}</span></div></Dialog>
    </Show>
    <Show when={isMine()}><Dialog title={`Call with ${peerName()}`} onClose={() => runtime.endCall()} actions={<>
      <button type="button" aria-label={runtime.state.isCallMuted ? "Unmute" : "Mute"} aria-pressed={runtime.state.isCallMuted} onClick={() => runtime.toggleMute()}><Icon name={runtime.state.isCallMuted ? "mic_off" : "mic"} size={18} /></button>
      <button type="button" aria-label={runtime.state.isCallCameraOn ? "Turn camera off" : "Turn camera on"} aria-pressed={runtime.state.isCallCameraOn} onClick={() => void runtime.toggleCamera()}><Icon name={runtime.state.isCallCameraOn ? "video" : "video_off"} size={18} /></button>
      <button type="button" aria-label="Move call to phone" disabled={runtime.state.call.phase !== "active" || !!runtime.state.call.movingTo} onClick={() => runtime.moveCallToPhone()}><Icon name="smartphone" size={18} /></button>
      <button type="button" class="dex-call-danger" onClick={() => runtime.endCall()}><Icon name="call_end" size={18} />End call</button>
    </>}><div class="dex-call-media"><video ref={(element) => { remoteVideo = element; element.srcObject = runtime.state.callRemoteStream; if (element.srcObject) void element.play().catch(() => setNeedsPlay(true)); }} autoplay playsinline aria-label="Remote video" />
        <Show when={!runtime.state.call.remoteCamOn}><div class="dex-call-avatar">{peerName().slice(0, 1).toUpperCase()}</div></Show>
        <Show when={runtime.state.isCallCameraOn}><video ref={(element) => { localVideo = element; element.srcObject = runtime.state.callLocalStream; if (element.srcObject) void element.play().catch(() => undefined); }} autoplay muted playsinline aria-label="Your video" /></Show>
        <Show when={needsPlay()}><button type="button" onClick={() => void remoteVideo?.play().then(() => setNeedsPlay(false))}>Play call audio</button></Show>
      </div><p class="dex-call-status">{callStatus()}</p></Dialog></Show>
    <Show when={isMirror()}><div class="dex-call-banner" role="status"><Icon name="call" size={16} />Call with {peerName()} · {runtime.state.call.seat === "phone" ? "on phone" : "on another Dex client"} · {callStatus()}</div></Show>
  </>;
}

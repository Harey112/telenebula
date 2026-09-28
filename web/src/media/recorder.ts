export interface VoiceClip { blob: Blob; mime: string; durationMs: number }

const preferred = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4", "audio/ogg;codecs=opus", "audio/ogg"];

export function voiceExtension(mime: string): string {
  if (mime.startsWith("audio/webm")) return "webm";
  if (mime.startsWith("audio/mp4")) return "m4a";
  if (mime.startsWith("audio/ogg")) return "ogg";
  return "bin";
}

export class VoiceRecorder {
  private recorder: MediaRecorder | null = null;
  private stream: MediaStream | null = null;
  private chunks: Blob[] = [];
  private startedAt = 0;
  private starting: Promise<boolean> | null = null;
  private generation = 0;

  get isSupported(): boolean { return typeof MediaRecorder !== "undefined" && !!navigator.mediaDevices?.getUserMedia; }

  start(): Promise<boolean> {
    if (this.recorder) return Promise.resolve(true);
    if (this.starting) return this.starting;
    const attempt = this.begin(this.generation);
    this.starting = attempt;
    void attempt.finally(() => { if (this.starting === attempt) this.starting = null; });
    return attempt;
  }

  private async begin(generation: number): Promise<boolean> {
    if (!this.isSupported) return false;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (generation !== this.generation) {
        stream.getTracks().forEach((track) => track.stop());
        return false;
      }
      this.stream = stream;
      const mimeType = preferred.find((mime) => MediaRecorder.isTypeSupported(mime));
      const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
      this.recorder = recorder;
      this.chunks = [];
      recorder.ondataavailable = (event) => { if (event.data.size > 0) this.chunks.push(event.data); };
      recorder.start(250);
      this.startedAt = Date.now();
      return true;
    } catch {
      this.release();
      return false;
    }
  }

  stop(): Promise<VoiceClip | null> {
    const recorder = this.recorder;
    if (!recorder) return Promise.resolve(null);
    this.recorder = null;
    const durationMs = Date.now() - this.startedAt;
    return new Promise((resolve) => {
      recorder.onstop = () => {
        const mime = recorder.mimeType.split(";")[0] || "audio/webm";
        const blob = new Blob(this.chunks, { type: mime });
        this.release();
        resolve(blob.size > 0 ? { blob, mime, durationMs } : null);
      };
      recorder.onerror = () => { this.release(); resolve(null); };
      try { recorder.stop(); } catch { this.release(); resolve(null); }
    });
  }

  cancel(): void {
    this.generation += 1;
    const recorder = this.recorder;
    this.recorder = null;
    if (recorder && recorder.state !== "inactive") {
      recorder.onstop = null;
      try { recorder.stop(); } catch { this.release(); }
    }
    this.release();
  }

  private release(): void {
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = null;
    this.chunks = [];
  }
}

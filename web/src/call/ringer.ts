export class Ringer {
  private context: AudioContext | null = null;
  private timer: number | null = null;

  start(): void {
    if (this.timer !== null || typeof AudioContext === "undefined") return;
    try { this.context = new AudioContext(); }
    catch { return; }
    this.beep();
    this.timer = window.setInterval(() => this.beep(), 2_500);
  }

  private beep(): void {
    const context = this.context;
    if (!context || context.state === "closed") return;
    for (let index = 0; index < 2; index += 1) {
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      oscillator.type = "sine";
      oscillator.frequency.value = index === 0 ? 880 : 660;
      gain.gain.value = 0.08;
      oscillator.connect(gain);
      gain.connect(context.destination);
      const at = context.currentTime + index * 0.45;
      oscillator.start(at);
      oscillator.stop(at + 0.35);
    }
  }

  stop(): void {
    if (this.timer !== null) window.clearInterval(this.timer);
    this.timer = null;
    const context = this.context;
    this.context = null;
    if (context) void context.close().catch(() => undefined);
  }
}

// Short WebAudio beep for new kitchen tickets. Silently does nothing where audio is unavailable.

type AudioCtor = typeof AudioContext;
let ctx: AudioContext | null = null;

export function beep(frequency = 880, durationMs = 180): void {
  try {
    const w = globalThis as unknown as { AudioContext?: AudioCtor; webkitAudioContext?: AudioCtor };
    const Ctor = w.AudioContext ?? w.webkitAudioContext;
    if (!Ctor) return;
    ctx ??= new Ctor();
    if (ctx.state === 'suspended') void ctx.resume();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.value = frequency;
    const t0 = ctx.currentTime;
    gain.gain.setValueAtTime(0.0001, t0);
    gain.gain.exponentialRampToValueAtTime(0.3, t0 + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + durationMs / 1000);
    osc.connect(gain).connect(ctx.destination);
    osc.start(t0);
    osc.stop(t0 + durationMs / 1000 + 0.02);
  } catch {
    /* audio blocked or unsupported */
  }
}

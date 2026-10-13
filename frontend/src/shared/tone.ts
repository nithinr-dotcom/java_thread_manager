export type Tone = 'ok' | 'warn' | 'bad' | 'info' | 'muted';

export function statusTone(status: number): Tone {
  if (status >= 200 && status < 300) return 'ok';
  if (status === 0 || status >= 500) return 'bad';
  if (status === 409 || status === 422) return 'warn';
  return 'bad';
}

// Business-date helpers, pure (Intl only).

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

export function isBusinessDate(s: string): boolean {
  if (!DATE_RE.test(s)) return false;
  const d = new Date(`${s}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === s;
}

/** Today's `YYYY-MM-DD` in the given IANA zone (branch zone); falls back to UTC for bad zones. */
export function businessDate(zone: string, nowMs: number = Date.now()): string {
  const fmt = (timeZone: string) => {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(new Date(nowMs));
    const get = (t: string) => parts.find((p) => p.type === t)?.value ?? '';
    return `${get('year')}-${get('month')}-${get('day')}`;
  };
  try {
    return fmt(zone);
  } catch {
    return fmt('UTC');
  }
}

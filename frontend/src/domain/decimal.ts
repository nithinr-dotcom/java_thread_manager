// Parsing of user-typed money amounts for REQUEST bodies (payment amount, tendered, unit price).
// This is input validation only: the client never adds, subtracts or rounds money.

export type DecimalError = 'EMPTY' | 'FORMAT' | 'SCALE' | 'NOT_POSITIVE' | 'NEGATIVE';

export type DecimalResult = { ok: true; value: number } | { ok: false; error: DecimalError };

const DECIMAL_RE = /^(\d{1,12})(?:[.,](\d+))?$/;

/**
 * Parse a non-negative decimal typed by a user ("12", "12.5", "12,50"). More fraction digits than
 * the currency allows is an error (the server would answer 400). `positive` additionally rejects 0.
 */
export function parseAmount(
  input: string,
  fractionDigits: number,
  opts: { positive?: boolean } = {},
): DecimalResult {
  const s = input.trim();
  if (s === '') return { ok: false, error: 'EMPTY' };
  if (s.startsWith('-')) return { ok: false, error: 'NEGATIVE' };
  const m = DECIMAL_RE.exec(s);
  if (!m) return { ok: false, error: 'FORMAT' };
  const frac = m[2] ?? '';
  if (frac.length > fractionDigits) return { ok: false, error: 'SCALE' };
  const value = Number(frac ? `${m[1]}.${frac}` : m[1]);
  if (opts.positive && value <= 0) return { ok: false, error: 'NOT_POSITIVE' };
  return { ok: true, value };
}

/** Render a server amount into an input field (plain digits, '.' separator, currency scale). */
export function amountToInput(value: number, fractionDigits: number): string {
  return value.toFixed(fractionDigits);
}

/** Parse an integer in [min, max]; returns null when invalid. */
export function parseIntInRange(input: string, min: number, max: number): number | null {
  const s = input.trim();
  if (!/^[+-]?\d+$/.test(s)) return null;
  const n = Number(s);
  return Number.isSafeInteger(n) && n >= min && n <= max ? n : null;
}

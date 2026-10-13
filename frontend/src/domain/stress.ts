// Pure analysis of a batch of concurrent checkout results (Scenarios A, B, C).

export interface StressSample {
  /** HTTP status, 0 = network error. */
  status: number;
  code?: string | undefined;
  orderId?: string | undefined;
  orderNumber?: string | undefined;
  replayed?: boolean | null | undefined;
  elapsedMs: number;
}

export interface Check {
  id: string;
  label: string;
  pass: boolean;
  detail: string;
}

/** Bucket label: "201", "200 replayed", "409 OUT_OF_STOCK", "0 NETWORK_ERROR". */
export function bucketOf(s: StressSample): string {
  if (s.status >= 200 && s.status < 300) {
    return s.replayed ? `${s.status} replayed` : String(s.status);
  }
  return s.code ? `${s.status} ${s.code}` : String(s.status);
}

export function countByBucket(samples: readonly StressSample[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const s of samples) {
    const b = bucketOf(s);
    out[b] = (out[b] ?? 0) + 1;
  }
  return out;
}

export const isSuccess = (s: StressSample): boolean => s.status >= 200 && s.status < 300;

// ---------------------------------------------------------------- fiscal numbers

export interface FiscalNumber {
  branch: string;
  date: string;
  seq: number;
}

const FISCAL_RE = /^ORD-([A-Za-z0-9]+)-(\d{8})-(\d{4,})$/;

/** Parse `ORD-{BRANCH}-{YYYYMMDD}-{NNNN}`; null if malformed. */
export function parseOrderNumber(n: string): FiscalNumber | null {
  const m = FISCAL_RE.exec(n);
  if (!m || m[1] === undefined || m[2] === undefined || m[3] === undefined) return null;
  return { branch: m[1], date: m[2], seq: Number.parseInt(m[3], 10) };
}

export interface FiscalCheck {
  pass: boolean;
  unique: boolean;
  contiguous: boolean;
  malformed: string[];
  duplicates: string[];
  /** Human-readable gap descriptions, e.g. "HOSP01/20261013: missing 0005". */
  gaps: string[];
  /** Per-series range, e.g. "HOSP01/20261013: 0011..0020". */
  ranges: string[];
}

const pad = (n: number) => String(n).padStart(4, '0');

/**
 * Verify that the new order numbers are unique and form a contiguous run per (branch, day) series.
 * If `previous` (the latest order number before the batch) is in the same series, the run must
 * start exactly at previous + 1 (no gap between the old and the new orders).
 */
export function checkFiscalSequence(
  orderNumbers: readonly string[],
  previous?: string | null,
): FiscalCheck {
  const malformed: string[] = [];
  const duplicates: string[] = [];
  const seen = new Set<string>();
  const series = new Map<string, number[]>();

  for (const n of orderNumbers) {
    if (seen.has(n)) {
      duplicates.push(n);
      continue;
    }
    seen.add(n);
    const p = parseOrderNumber(n);
    if (!p) {
      malformed.push(n);
      continue;
    }
    const k = `${p.branch}/${p.date}`;
    const arr = series.get(k) ?? [];
    arr.push(p.seq);
    series.set(k, arr);
  }

  const prev = previous ? parseOrderNumber(previous) : null;
  const gaps: string[] = [];
  const ranges: string[] = [];
  for (const [k, seqs] of [...series.entries()].sort(([a], [b]) => (a < b ? -1 : 1))) {
    seqs.sort((a, b) => a - b);
    const first = seqs[0] as number;
    const last = seqs[seqs.length - 1] as number;
    ranges.push(`${k}: ${pad(first)}..${pad(last)}`);
    if (prev && `${prev.branch}/${prev.date}` === k && first !== prev.seq + 1) {
      gaps.push(`${k}: expected first ${pad(prev.seq + 1)} after ${previous}, got ${pad(first)}`);
    }
    for (let i = 1; i < seqs.length; i++) {
      const a = seqs[i - 1] as number;
      const b = seqs[i] as number;
      if (b !== a + 1) {
        gaps.push(
          b - a === 2
            ? `${k}: missing ${pad(a + 1)}`
            : `${k}: missing ${pad(a + 1)}..${pad(b - 1)}`,
        );
      }
    }
  }
  const unique = duplicates.length === 0;
  const contiguous = gaps.length === 0 && malformed.length === 0;
  return { pass: unique && contiguous, unique, contiguous, malformed, duplicates, gaps, ranges };
}

// ---------------------------------------------------------------- latency

export interface LatencyStats {
  count: number;
  min: number;
  p50: number;
  p95: number;
  max: number;
}

/** Nearest-rank percentile on a sorted array. */
export function percentile(sorted: readonly number[], p: number): number {
  if (sorted.length === 0) return 0;
  const rank = Math.ceil((p / 100) * sorted.length);
  return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1] as number;
}

export function latencyStats(ms: readonly number[]): LatencyStats {
  const s = [...ms].sort((a, b) => a - b);
  return {
    count: s.length,
    min: s[0] ?? 0,
    p50: percentile(s, 50),
    p95: percentile(s, 95),
    max: s[s.length - 1] ?? 0,
  };
}

// ---------------------------------------------------------------- scenario analyses

const ALLOWED_RACE_FAILURES = new Set(['OUT_OF_STOCK', 'BUSY_RETRY']);

export interface StockRaceInput {
  samples: readonly StressSample[];
  /** Units per request (the console uses 1). */
  quantity: number;
  stockBefore: number;
  stockAfter: number | null;
  /** Latest order number before the race, to check no gap against existing orders. */
  previousOrderNumber?: string | null;
}

export function analyzeStockRace(input: StockRaceInput): Check[] {
  const { samples, quantity, stockBefore, stockAfter } = input;
  const n = samples.length;
  const successes = samples.filter(isSuccess);
  const created = successes.length;
  const busy = samples.filter((s) => s.code === 'BUSY_RETRY').length;
  const expected = Math.min(n, Math.floor(stockBefore / quantity));
  const checks: Check[] = [];

  // With BUSY_RETRY some requests may legitimately not get a unit; never more than expected.
  const noOversell = created * quantity <= stockBefore;
  checks.push({
    id: 'no-oversell',
    label: 'Zero oversell',
    pass: noOversell && (busy > 0 ? created <= expected : created === expected),
    detail:
      `${created} succeeded × ${quantity} unit(s) vs. ${stockBefore} in stock; expected ${expected}` +
      (busy > 0 ? ` (minus up to ${busy} BUSY_RETRY)` : ''),
  });

  const unitsSold = created * quantity;
  checks.push({
    id: 'stock-conservation',
    label: 'Stock after = before − sold',
    pass: stockAfter !== null && stockAfter === stockBefore - unitsSold && stockAfter >= 0,
    detail:
      stockAfter === null
        ? 'could not read stock after the race'
        : `${stockBefore} − ${unitsSold} = ${stockBefore - unitsSold}; observed ${stockAfter}`,
  });

  const fiscal = checkFiscalSequence(
    successes.map((s) => s.orderNumber ?? ''),
    input.previousOrderNumber,
  );
  checks.push({
    id: 'fiscal',
    label: 'Fiscal numbers unique, gap-free, contiguous',
    pass: fiscal.pass,
    detail:
      [
        fiscal.ranges.join('; '),
        fiscal.duplicates.length ? `duplicates: ${fiscal.duplicates.join(', ')}` : '',
        fiscal.malformed.length ? `malformed: ${fiscal.malformed.join(', ')}` : '',
        fiscal.gaps.join('; '),
      ]
        .filter(Boolean)
        .join(' | ') || 'no orders created',
  });

  const badFailures = samples.filter(
    (s) => !isSuccess(s) && !(s.code !== undefined && ALLOWED_RACE_FAILURES.has(s.code)),
  );
  checks.push({
    id: 'clean-failures',
    label: 'All failures are 409 OUT_OF_STOCK or 503 BUSY_RETRY',
    pass: badFailures.length === 0,
    detail:
      badFailures.length === 0
        ? `${n - created} clean failure(s)`
        : `unexpected: ${Object.entries(countByBucket(badFailures))
            .map(([k, v]) => `${k} ×${v}`)
            .join(', ')}`,
  });
  return checks;
}

export interface DuplicateStormInput {
  samples: readonly StressSample[];
  /** Units in the single basket. */
  quantity: number;
  stockBefore: number;
  stockAfter: number | null;
}

export function analyzeDuplicateStorm(input: DuplicateStormInput): Check[] {
  const { samples, quantity, stockBefore, stockAfter } = input;
  const created = samples.filter((s) => s.status === 201);
  const replays = samples.filter((s) => s.status === 200);
  const others = samples.filter((s) => s.status !== 201 && s.status !== 200);
  const busy = others.filter((s) => s.code === 'BUSY_RETRY').length;
  const ids = new Set(samples.filter(isSuccess).map((s) => s.orderId));
  const checks: Check[] = [];

  checks.push({
    id: 'one-created',
    label: 'Exactly one 201 Created',
    pass: created.length === 1,
    detail: `${created.length} × 201`,
  });
  const replayFlagged = replays.filter((s) => s.replayed === true).length;
  checks.push({
    id: 'replays',
    label: 'All others are 200 with Idempotent-Replayed: true',
    pass: replayFlagged === replays.length && others.length === 0,
    detail:
      `${replays.length} × 200 (${replayFlagged} flagged replayed)` +
      (others.length
        ? `; ${others.length} other(s): ${Object.entries(countByBucket(others))
            .map(([k, v]) => `${k} ×${v}`)
            .join(', ')}${busy ? ' — BUSY_RETRY is safe to retry with the same key' : ''}`
        : ''),
  });
  checks.push({
    id: 'same-order',
    label: 'All responses carry the same order id',
    pass: ids.size === 1,
    detail: `${ids.size} distinct order id(s)`,
  });
  checks.push({
    id: 'stock-once',
    label: 'Stock decremented exactly once',
    pass: stockAfter !== null && stockAfter === stockBefore - quantity,
    detail:
      stockAfter === null
        ? 'could not read stock after the storm'
        : `${stockBefore} − ${quantity} = ${stockBefore - quantity}; observed ${stockAfter}`,
  });
  return checks;
}

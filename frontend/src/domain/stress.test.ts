import { describe, expect, it } from 'vitest';
import {
  analyzeDuplicateStorm,
  analyzeStockRace,
  bucketOf,
  checkFiscalSequence,
  countByBucket,
  latencyStats,
  parseOrderNumber,
  percentile,
  type StressSample,
} from './stress';

const ord = (n: number, branch = 'HOSP01', date = '20261013') =>
  `ORD-${branch}-${date}-${String(n).padStart(4, '0')}`;

const ok = (n: number, ms = 10): StressSample => ({
  status: 201,
  orderId: `id-${n}`,
  orderNumber: ord(n),
  replayed: false,
  elapsedMs: ms,
});
const oos = (ms = 5): StressSample => ({ status: 409, code: 'OUT_OF_STOCK', elapsedMs: ms });

const byId = (checks: { id: string; pass: boolean }[]) =>
  Object.fromEntries(checks.map((c) => [c.id, c.pass]));

describe('parseOrderNumber', () => {
  it('parses the fiscal format', () => {
    expect(parseOrderNumber('ORD-HOSP01-20261013-0042')).toEqual({
      branch: 'HOSP01',
      date: '20261013',
      seq: 42,
    });
    expect(parseOrderNumber('ORD-HOSP01-20261013-12345')?.seq).toBe(12345);
  });
  it('rejects malformed numbers', () => {
    expect(parseOrderNumber('ORD-HOSP01-2026-0001')).toBeNull();
    expect(parseOrderNumber('')).toBeNull();
    expect(parseOrderNumber('ORD-HOSP01-20261013-01')).toBeNull();
  });
});

describe('checkFiscalSequence', () => {
  it('passes for a unique contiguous run in any order', () => {
    const r = checkFiscalSequence([ord(3), ord(1), ord(2)]);
    expect(r.pass).toBe(true);
    expect(r.ranges).toEqual(['HOSP01/20261013: 0001..0003']);
  });
  it('detects duplicates', () => {
    const r = checkFiscalSequence([ord(1), ord(2), ord(2)]);
    expect(r.unique).toBe(false);
    expect(r.duplicates).toEqual([ord(2)]);
    expect(r.pass).toBe(false);
  });
  it('detects gaps', () => {
    const r = checkFiscalSequence([ord(1), ord(2), ord(5)]);
    expect(r.contiguous).toBe(false);
    expect(r.gaps).toEqual(['HOSP01/20261013: missing 0003..0004']);
    expect(checkFiscalSequence([ord(1), ord(3)]).gaps).toEqual(['HOSP01/20261013: missing 0002']);
  });
  it('checks continuity with the previous order in the same series only', () => {
    expect(checkFiscalSequence([ord(11), ord(12)], ord(10)).pass).toBe(true);
    expect(checkFiscalSequence([ord(12), ord(13)], ord(10)).pass).toBe(false);
    expect(checkFiscalSequence([ord(1)], ord(10, 'HOSP01', '20261012')).pass).toBe(true);
  });
  it('treats series (branch/day) independently and flags malformed', () => {
    expect(checkFiscalSequence([ord(1), ord(2), ord(1, 'B2'), ord(2, 'B2')]).pass).toBe(true);
    const r = checkFiscalSequence([ord(1), 'garbage']);
    expect(r.malformed).toEqual(['garbage']);
    expect(r.pass).toBe(false);
  });
  it('passes for an empty batch', () => {
    expect(checkFiscalSequence([]).pass).toBe(true);
  });
});

describe('latency', () => {
  it('computes nearest-rank percentiles', () => {
    const xs = Array.from({ length: 100 }, (_, i) => i + 1);
    expect(percentile(xs, 50)).toBe(50);
    expect(percentile(xs, 95)).toBe(95);
    expect(latencyStats([30, 10, 20])).toEqual({ count: 3, min: 10, p50: 20, p95: 30, max: 30 });
    expect(latencyStats([])).toEqual({ count: 0, min: 0, p50: 0, p95: 0, max: 0 });
  });
});

describe('buckets', () => {
  it('labels and counts by status/code', () => {
    expect(bucketOf({ status: 200, replayed: true, elapsedMs: 0 })).toBe('200 replayed');
    expect(bucketOf({ status: 0, code: 'NETWORK_ERROR', elapsedMs: 0 })).toBe('0 NETWORK_ERROR');
    expect(countByBucket([ok(1), ok(2), oos()])).toEqual({ '201': 2, '409 OUT_OF_STOCK': 1 });
  });
});

describe('analyzeStockRace', () => {
  const race = (n: number, stock: number) => [
    ...Array.from({ length: Math.min(n, stock) }, (_, i) => ok(i + 1)),
    ...Array.from({ length: Math.max(0, n - stock) }, () => oos()),
  ];

  it('passes the canonical 10 stock / 50 terminals case', () => {
    const checks = analyzeStockRace({
      samples: race(50, 10),
      quantity: 1,
      stockBefore: 10,
      stockAfter: 0,
      previousOrderNumber: null,
    });
    expect(checks.every((c) => c.pass)).toBe(true);
  });

  it('fails on oversell', () => {
    const samples = [...race(50, 10), ok(11)].slice(0, 50);
    samples[49] = ok(11);
    const c = byId(analyzeStockRace({ samples, quantity: 1, stockBefore: 10, stockAfter: -1 }));
    expect(c['no-oversell']).toBe(false);
    expect(c['stock-conservation']).toBe(false);
  });

  it('fails when stock after does not match or is unknown', () => {
    const s = race(5, 10);
    expect(
      byId(analyzeStockRace({ samples: s, quantity: 1, stockBefore: 10, stockAfter: 4 }))[
        'stock-conservation'
      ],
    ).toBe(false);
    expect(
      byId(analyzeStockRace({ samples: s, quantity: 1, stockBefore: 10, stockAfter: null }))[
        'stock-conservation'
      ],
    ).toBe(false);
    expect(
      byId(analyzeStockRace({ samples: s, quantity: 1, stockBefore: 10, stockAfter: 5 }))[
        'stock-conservation'
      ],
    ).toBe(true);
  });

  it('tolerates BUSY_RETRY as a clean failure but rejects other errors', () => {
    const busy: StressSample = { status: 503, code: 'BUSY_RETRY', elapsedMs: 1 };
    const samples = [ok(1), ok(2), busy, oos()];
    const c = byId(analyzeStockRace({ samples, quantity: 1, stockBefore: 3, stockAfter: 1 }));
    expect(c['no-oversell']).toBe(true);
    expect(c['clean-failures']).toBe(true);

    const bad: StressSample = { status: 500, elapsedMs: 1 };
    expect(
      byId(analyzeStockRace({ samples: [ok(1), bad], quantity: 1, stockBefore: 1, stockAfter: 0 }))[
        'clean-failures'
      ],
    ).toBe(false);
  });

  it('fails when the fiscal run has a gap or a duplicate', () => {
    const gap = [ok(1), ok(3)];
    expect(
      byId(analyzeStockRace({ samples: gap, quantity: 1, stockBefore: 2, stockAfter: 0 })).fiscal,
    ).toBe(false);
    const dup = [ok(1), { ...ok(1), orderId: 'other' }];
    expect(
      byId(analyzeStockRace({ samples: dup, quantity: 1, stockBefore: 2, stockAfter: 0 })).fiscal,
    ).toBe(false);
  });

  it('fails when fewer than expected succeed without BUSY_RETRY', () => {
    const samples = [ok(1), oos(), oos()];
    expect(
      byId(analyzeStockRace({ samples, quantity: 1, stockBefore: 3, stockAfter: 2 }))[
        'no-oversell'
      ],
    ).toBe(false);
  });
});

describe('analyzeDuplicateStorm', () => {
  const replay = (): StressSample => ({ ...ok(1), status: 200, replayed: true });

  it('passes for one 201 and N-1 replays of the same order', () => {
    const samples = [replay(), ok(1), replay(), replay()];
    const checks = analyzeDuplicateStorm({ samples, quantity: 1, stockBefore: 5, stockAfter: 4 });
    expect(checks.every((c) => c.pass)).toBe(true);
  });

  it('fails on two creations / different order ids / double decrement', () => {
    const samples = [ok(1), ok(2), replay()];
    const c = byId(analyzeDuplicateStorm({ samples, quantity: 1, stockBefore: 5, stockAfter: 3 }));
    expect(c['one-created']).toBe(false);
    expect(c['same-order']).toBe(false);
    expect(c['stock-once']).toBe(false);
  });

  it('fails when a 200 lacks the replay flag or an error occurs', () => {
    const notFlagged: StressSample = { ...replay(), replayed: false };
    expect(
      byId(
        analyzeDuplicateStorm({
          samples: [ok(1), notFlagged],
          quantity: 1,
          stockBefore: 5,
          stockAfter: 4,
        }),
      ).replays,
    ).toBe(false);
    const busy: StressSample = { status: 503, code: 'BUSY_RETRY', elapsedMs: 1 };
    expect(
      byId(
        analyzeDuplicateStorm({
          samples: [ok(1), busy],
          quantity: 1,
          stockBefore: 5,
          stockAfter: 4,
        }),
      ).replays,
    ).toBe(false);
  });
});

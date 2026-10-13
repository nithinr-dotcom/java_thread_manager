import { describe, expect, it } from 'vitest';
import {
  attemptForCheckout,
  autoRetryDelay,
  classifyOutcome,
  decodePending,
  defaultKeyGenerator,
  encodePending,
  isDefinitiveStatus,
  isSettled,
  MAX_AUTO_SENDS,
  newAttempt,
  newPendingAction,
  recordOutcome,
  resolveSend,
  samePayload,
  type PendingAction,
} from './idempotency';

const seq = () => {
  let i = 0;
  return () => `k${++i}`;
};

describe('classifyOutcome', () => {
  it.each([
    [201, 'created'],
    [200, 'replayed'],
    [409, 'rejected'],
    [422, 'rejected'],
    [400, 'rejected'],
    [401, 'rejected'],
    [408, 'retryable'],
    [429, 'retryable'],
    [503, 'retryable'],
    [500, 'retryable'],
    [0, 'retryable'],
  ] as const)('%i -> %s', (status, kind) => {
    expect(classifyOutcome(status)).toBe(kind);
  });

  it('isDefinitiveStatus matches the classification', () => {
    for (const s of [0, 200, 201, 204, 400, 404, 408, 409, 422, 429, 500, 502, 503]) {
      expect(isDefinitiveStatus(s)).toBe(classifyOutcome(s) !== 'retryable');
    }
  });
});

describe('checkout attempt', () => {
  it('generates UUID keys by default', () => {
    expect(defaultKeyGenerator()).toMatch(/^[0-9a-f-]{36}$/);
    expect(defaultKeyGenerator()).not.toBe(defaultKeyGenerator());
  });

  it('uses the same key until something is sent', () => {
    const gen = seq();
    const a = newAttempt(gen);
    expect(a).toEqual({ key: 'k1', sends: 0, lastOutcome: null });
    expect(attemptForCheckout(a, gen)).toBe(a);
  });

  it('keeps the key across ambiguous outcomes (network error, 503)', () => {
    const gen = seq();
    let a = newAttempt(gen);
    a = recordOutcome(a, 0);
    expect(isSettled(a)).toBe(false);
    a = recordOutcome(attemptForCheckout(a, gen), 503);
    expect(a.key).toBe('k1');
    expect(a.sends).toBe(2);
    a = recordOutcome(attemptForCheckout(a, gen), 201);
    expect(a.key).toBe('k1');
    expect(isSettled(a)).toBe(true);
  });

  it('rotates the key only after a definitive outcome', () => {
    const gen = seq();
    const created = recordOutcome(newAttempt(gen), 201);
    expect(attemptForCheckout(created, gen).key).toBe('k2');
    const rejected = recordOutcome(newAttempt(gen), 409);
    expect(attemptForCheckout(rejected, gen)).toEqual({ key: 'k4', sends: 0, lastOutcome: null });
  });
});

describe('pending action', () => {
  const checkout = { kind: 'checkout' as const, items: [{ sku: 'A', quantity: 2 }] };

  it('is created with a fresh key and no sends', () => {
    const p = newPendingAction(checkout, seq(), 1000);
    expect(p).toEqual({
      v: 1,
      key: 'k1',
      payload: checkout,
      createdAt: 1000,
      sends: 0,
      lastStatus: null,
      lastCode: null,
    });
  });

  it('keeps the same key across ambiguous outcomes and settles on definitive ones', () => {
    const p = newPendingAction(checkout, seq(), 0);
    const r1 = resolveSend(p, 0, 'TIMEOUT');
    expect(r1.settled).toBe(false);
    if (r1.settled) throw new Error('unreachable');
    expect(r1.next).toMatchObject({ key: 'k1', sends: 1, lastStatus: 0, lastCode: 'TIMEOUT' });
    const r2 = resolveSend(r1.next, 503, 'BUSY_RETRY');
    if (r2.settled) throw new Error('unreachable');
    expect(r2.next).toMatchObject({ key: 'k1', sends: 2, lastStatus: 503 });
    expect(resolveSend(r2.next, 429).settled).toBe(false);
    expect(resolveSend(r2.next, 408).settled).toBe(false);
    expect(resolveSend(r2.next, 200)).toEqual({ settled: true, outcome: 'replayed' });
    expect(resolveSend(r2.next, 201)).toEqual({ settled: true, outcome: 'created' });
    expect(resolveSend(r2.next, 409)).toEqual({ settled: true, outcome: 'rejected' });
  });

  it('auto-retries with backoff a bounded number of times', () => {
    let p: PendingAction = newPendingAction(checkout, seq(), 0);
    expect(autoRetryDelay(p)).toBeNull(); // never sent: nothing to retry yet
    const delays: (number | null)[] = [];
    for (let i = 0; i < MAX_AUTO_SENDS; i++) {
      const r = resolveSend(p, 503);
      if (r.settled) throw new Error('unreachable');
      p = r.next;
      delays.push(autoRetryDelay(p));
    }
    expect(delays).toEqual([1000, 2000, 4000, null]);
  });

  it('round-trips through storage encoding', () => {
    const payloads = [
      checkout,
      {
        kind: 'payment' as const,
        orderId: 'o1',
        request: { method: 'CASH' as const, amount: 27.39, tendered: 30 },
      },
      {
        kind: 'payment' as const,
        orderId: 'o1',
        request: { method: 'CARD' as const, amount: 5, reference: 'AUTH-1' },
      },
      { kind: 'cancel' as const, orderId: 'o1', reason: 'Customer left' },
      { kind: 'void' as const, orderId: 'o1', paymentId: 'p1', reason: 'Card declined' },
      { kind: 'restock' as const, sku: 'A', request: { quantity: 10, note: 'Delivery' } },
      { kind: 'restock' as const, sku: 'A', request: { quantity: 10 } },
      {
        kind: 'adjust' as const,
        sku: 'A',
        request: { delta: -2, reason: 'WASTE' as const, note: 'Expired' },
      },
      {
        kind: 'refund' as const,
        orderId: 'o1',
        request: {
          lines: [{ lineNo: 1, quantity: 1 }],
          restock: true,
          method: 'CASH' as const,
          reason: 'Cold',
        },
      },
    ];
    for (const payload of payloads) {
      const p = newPendingAction(payload, seq(), 5);
      expect(decodePending(encodePending(p))).toEqual(p);
    }
  });

  it('rejects malformed stored values', () => {
    expect(decodePending(null)).toBeNull();
    expect(decodePending('')).toBeNull();
    expect(decodePending('{oops')).toBeNull();
    expect(decodePending('[]')).toBeNull();
    const good = JSON.parse(encodePending(newPendingAction(checkout, seq(), 0))) as Record<
      string,
      unknown
    >;
    expect(decodePending(JSON.stringify({ ...good, v: 2 }))).toBeNull();
    expect(decodePending(JSON.stringify({ ...good, key: '' }))).toBeNull();
    expect(decodePending(JSON.stringify({ ...good, key: 'x'.repeat(129) }))).toBeNull();
    expect(
      decodePending(JSON.stringify({ ...good, payload: { kind: 'checkout', items: [] } })),
    ).toBeNull();
    expect(decodePending(JSON.stringify({ ...good, payload: { kind: 'nope' } }))).toBeNull();
    expect(
      decodePending(JSON.stringify({ ...good, payload: { kind: 'void', orderId: 'o' } })),
    ).toBeNull();
    expect(
      decodePending(
        JSON.stringify({
          ...good,
          payload: { kind: 'adjust', sku: 'A', request: { delta: 1, reason: 'SALE' } },
        }),
      ),
    ).toBeNull();
    expect(
      decodePending(
        JSON.stringify({
          ...good,
          payload: { kind: 'payment', orderId: 'o', request: { method: 'CASH', amount: 1 } },
        }),
      ),
    ).toBeNull();
  });

  it('compares payloads structurally', () => {
    expect(samePayload(checkout, { kind: 'checkout', items: [{ sku: 'A', quantity: 2 }] })).toBe(
      true,
    );
    expect(samePayload(checkout, { kind: 'checkout', items: [{ sku: 'A', quantity: 3 }] })).toBe(
      false,
    );
  });
});

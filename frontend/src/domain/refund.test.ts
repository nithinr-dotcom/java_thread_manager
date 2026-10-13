import { describe, expect, it } from 'vitest';
import {
  isCancellable,
  isRefundable,
  isVoidable,
  remainingQty,
  selectAll,
  setRefundQty,
  validateRefund,
  validReason,
  type RefundForm,
} from './refund';

const lines = [
  { lineNo: 1, quantity: 2, refundedQuantity: 0 },
  { lineNo: 2, quantity: 3, refundedQuantity: 3 },
  { lineNo: 3, quantity: 1, refundedQuantity: 0 },
];

const form = (over: Partial<RefundForm> = {}): RefundForm => ({
  status: 'PAID',
  lines,
  selection: { 1: 1 },
  restock: true,
  method: 'CASH',
  reason: ' Cold food ',
  ...over,
});

describe('order state rules', () => {
  it('refunds only PAID / PARTIALLY_REFUNDED orders', () => {
    expect(isRefundable('PAID')).toBe(true);
    expect(isRefundable('PARTIALLY_REFUNDED')).toBe(true);
    expect(isRefundable('PLACED')).toBe(false);
    expect(isRefundable('REFUNDED')).toBe(false);
    expect(isRefundable('CANCELLED')).toBe(false);
  });
  it('cancels only PLACED orders without unvoided payments', () => {
    expect(isCancellable({ status: 'PLACED', payments: [] })).toBe(true);
    expect(isCancellable({ status: 'PLACED', payments: [{ voidedAt: null }] })).toBe(false);
    expect(
      isCancellable({ status: 'PLACED', payments: [{ voidedAt: '2026-10-13T12:00:00Z' }] }),
    ).toBe(true);
    expect(isCancellable({ status: 'PAID', payments: [] })).toBe(false);
  });
  it('voids only unvoided payments of PLACED orders', () => {
    expect(isVoidable({ status: 'PLACED' }, { voidedAt: null })).toBe(true);
    expect(isVoidable({ status: 'PLACED' }, { voidedAt: 'x' })).toBe(false);
    expect(isVoidable({ status: 'PAID' }, { voidedAt: null })).toBe(false);
  });
});

describe('selection', () => {
  it('computes remaining quantities', () => {
    expect(lines.map(remainingQty)).toEqual([2, 0, 1]);
  });

  it('clamps quantities to 0..remaining and drops zeros', () => {
    const l1 = lines[0]!;
    expect(setRefundQty({}, l1, 5)).toEqual({ 1: 2 });
    expect(setRefundQty({ 1: 2 }, l1, 0)).toEqual({});
    expect(setRefundQty({}, l1, -1)).toEqual({});
    expect(setRefundQty({}, l1, 1.7)).toEqual({ 1: 1 });
    expect(setRefundQty({}, l1, Number.NaN)).toEqual({});
  });

  it('selects everything still refundable', () => {
    expect(selectAll(lines)).toEqual({ 1: 2, 3: 1 });
  });
});

describe('validateRefund', () => {
  it('builds a sorted request with a trimmed reason', () => {
    const r = validateRefund(form({ selection: { 3: 1, 1: 2 } }));
    expect(r).toEqual({
      ok: true,
      request: {
        lines: [
          { lineNo: 1, quantity: 2 },
          { lineNo: 3, quantity: 1 },
        ],
        restock: true,
        method: 'CASH',
        reason: 'Cold food',
      },
    });
  });

  it('reports every problem', () => {
    const r = validateRefund(
      form({ status: 'PLACED', selection: { 2: 1, 9: 1, 1: 3 }, reason: '   ' }),
    );
    expect(r.ok).toBe(false);
    if (r.ok) throw new Error('unreachable');
    expect(r.errors).toEqual([
      { code: 'NOT_REFUNDABLE' },
      { code: 'EXCEEDS_REMAINING', lineNo: 1, remaining: 2 },
      { code: 'EXCEEDS_REMAINING', lineNo: 2, remaining: 0 },
      { code: 'UNKNOWN_LINE', lineNo: 9 },
      { code: 'REASON_REQUIRED' },
    ]);
  });

  it('requires a selection and a reason of at most 200 chars', () => {
    const r = validateRefund(form({ selection: {}, reason: 'x'.repeat(201) }));
    if (r.ok) throw new Error('unreachable');
    expect(r.errors.map((e) => e.code)).toEqual(['NOTHING_SELECTED', 'REASON_TOO_LONG']);
  });
});

describe('validReason', () => {
  it('accepts 1..200 trimmed chars', () => {
    expect(validReason('  left ')).toBe('left');
    expect(validReason('   ')).toBeNull();
    expect(validReason('x'.repeat(201))).toBeNull();
  });
});

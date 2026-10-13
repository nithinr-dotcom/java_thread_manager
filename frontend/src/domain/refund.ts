// Refund / cancel rules on the client, pure. Quantities only — refund AMOUNTS are computed by the
// server (the last refund of a line gets the exact remainder), never here.

import type { OrderStatus, PaymentMethod, RefundRequest } from '../api/types';

export interface RefundableLine {
  lineNo: number;
  quantity: number;
  refundedQuantity: number;
}

/** lineNo → quantity to refund (0 / absent = not refunded). */
export type RefundSelection = Readonly<Record<number, number>>;

export const REASON_MAX = 200;

export function isRefundable(status: OrderStatus): boolean {
  return status === 'PAID' || status === 'PARTIALLY_REFUNDED';
}

interface PaymentState {
  voidedAt: string | null;
}

/** Only a PLACED order without unvoided payments can be cancelled (void them first). */
export function isCancellable(order: {
  status: OrderStatus;
  payments: readonly PaymentState[];
}): boolean {
  return order.status === 'PLACED' && order.payments.every((p) => p.voidedAt !== null);
}

/** A payment can be voided (tender handed back) only while the order is still PLACED. */
export function isVoidable(order: { status: OrderStatus }, payment: PaymentState): boolean {
  return order.status === 'PLACED' && payment.voidedAt === null;
}

export function remainingQty(l: RefundableLine): number {
  return Math.max(0, l.quantity - l.refundedQuantity);
}

/** Set a line's refund quantity, clamped to 0..remaining (integers only). */
export function setRefundQty(
  sel: RefundSelection,
  line: RefundableLine,
  qty: number,
): RefundSelection {
  const q = Number.isFinite(qty) ? Math.min(remainingQty(line), Math.max(0, Math.trunc(qty))) : 0;
  const next: Record<number, number> = { ...sel };
  if (q === 0) delete next[line.lineNo];
  else next[line.lineNo] = q;
  return next;
}

/** Select everything still refundable. */
export function selectAll(lines: readonly RefundableLine[]): RefundSelection {
  const next: Record<number, number> = {};
  for (const l of lines) if (remainingQty(l) > 0) next[l.lineNo] = remainingQty(l);
  return next;
}

export type RefundError =
  | { code: 'NOT_REFUNDABLE' }
  | { code: 'NOTHING_SELECTED' }
  | { code: 'UNKNOWN_LINE'; lineNo: number }
  | { code: 'EXCEEDS_REMAINING'; lineNo: number; remaining: number }
  | { code: 'REASON_REQUIRED' }
  | { code: 'REASON_TOO_LONG' };

export interface RefundForm {
  status: OrderStatus;
  lines: readonly RefundableLine[];
  selection: RefundSelection;
  restock: boolean;
  method: PaymentMethod;
  reason: string;
}

export type RefundValidation =
  | { ok: true; request: RefundRequest }
  | { ok: false; errors: RefundError[] };

/** Validate the form and build the request body (lines sorted by lineNo, zero lines dropped). */
export function validateRefund(f: RefundForm): RefundValidation {
  const errors: RefundError[] = [];
  if (!isRefundable(f.status)) errors.push({ code: 'NOT_REFUNDABLE' });
  const byNo = new Map(f.lines.map((l) => [l.lineNo, l]));
  const picked = Object.entries(f.selection)
    .map(([no, q]) => ({ lineNo: Number(no), quantity: q }))
    .filter((l) => l.quantity > 0)
    .sort((a, b) => a.lineNo - b.lineNo);
  if (picked.length === 0) errors.push({ code: 'NOTHING_SELECTED' });
  for (const p of picked) {
    const line = byNo.get(p.lineNo);
    if (!line) errors.push({ code: 'UNKNOWN_LINE', lineNo: p.lineNo });
    else if (!Number.isInteger(p.quantity) || p.quantity > remainingQty(line))
      errors.push({ code: 'EXCEEDS_REMAINING', lineNo: p.lineNo, remaining: remainingQty(line) });
  }
  const reason = f.reason.trim();
  if (reason === '') errors.push({ code: 'REASON_REQUIRED' });
  else if (reason.length > REASON_MAX) errors.push({ code: 'REASON_TOO_LONG' });
  if (errors.length > 0) return { ok: false, errors };
  return { ok: true, request: { lines: picked, restock: f.restock, method: f.method, reason } };
}

/** Validate a cancel reason (1..200 chars after trimming). Returns the trimmed reason or null. */
export function validReason(reason: string): string | null {
  const r = reason.trim();
  return r.length >= 1 && r.length <= REASON_MAX ? r : null;
}

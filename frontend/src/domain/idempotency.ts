// Idempotency-Key lifecycle for logical actions (checkout, payment, void, cancel, refund, restock,
// stock adjustment).
//
// Rule: the key is generated once per logical action and reused verbatim on every retry.
// It is rotated only after a *definitive* outcome: a 2xx, or a 4xx other than 408/429.
// Ambiguous outcomes (network error / timeout = status 0, 408, 429, 503 BUSY_RETRY, other 5xx)
// keep the key, because the server may already have committed — the retry must get the replay.
//
// A PendingAction is the durable form of "a request I sent (or am about to send) whose outcome I
// don't know yet". It is persisted BEFORE sending and removed only after a definitive outcome, so
// a reload / crash in between can re-send with the SAME key.

import type {
  AdjustmentRequest,
  CheckoutItem,
  PaymentRequest,
  RefundRequest,
  RestockRequest,
} from '../api/types';
import { backoffDelay, RETRY_BACKOFF, type BackoffPolicy } from './backoff';

export type KeyGenerator = () => string;

export const defaultKeyGenerator: KeyGenerator = () => crypto.randomUUID();

/** True when the status is a final answer from the server (rotate the key afterwards). */
export function isDefinitiveStatus(status: number): boolean {
  if (status >= 200 && status < 300) return true;
  if (status >= 400 && status < 500) return status !== 408 && status !== 429;
  return false;
}

export type OutcomeKind =
  /** 201: new resource committed. */
  | 'created'
  /** other 2xx (200 + Idempotent-Replayed: true on replays). */
  | 'replayed'
  /** 4xx (except 408/429): definitive business/validation rejection; nothing committed. */
  | 'rejected'
  /** 0 (network/timeout), 408, 429, 5xx: unknown whether committed — retry with the SAME key. */
  | 'retryable';

export function classifyOutcome(status: number): OutcomeKind {
  if (status === 201) return 'created';
  if (status >= 200 && status < 300) return 'replayed';
  if (isDefinitiveStatus(status)) return 'rejected';
  return 'retryable';
}

export function isDefinitive(kind: OutcomeKind): boolean {
  return kind !== 'retryable';
}

// ---------------------------------------------------------------- simple in-memory attempt

export interface CheckoutAttempt {
  readonly key: string;
  /** Number of requests sent with this key. */
  readonly sends: number;
  /** Outcome of the most recent send, or null if never sent. */
  readonly lastOutcome: OutcomeKind | null;
}

export function newAttempt(gen: KeyGenerator = defaultKeyGenerator): CheckoutAttempt {
  return { key: gen(), sends: 0, lastOutcome: null };
}

/** True when the attempt has reached a definitive outcome and a *new* action needs a new key. */
export function isSettled(a: CheckoutAttempt): boolean {
  return a.lastOutcome !== null && isDefinitive(a.lastOutcome);
}

/** Keep the key while the outcome is unknown (or nothing was sent); rotate after a definitive one. */
export function attemptForCheckout(
  a: CheckoutAttempt,
  gen: KeyGenerator = defaultKeyGenerator,
): CheckoutAttempt {
  return isSettled(a) ? newAttempt(gen) : a;
}

/** Record that a request was sent with this key and what came back. */
export function recordOutcome(a: CheckoutAttempt, status: number): CheckoutAttempt {
  return { key: a.key, sends: a.sends + 1, lastOutcome: classifyOutcome(status) };
}

// ---------------------------------------------------------------- durable pending action

export type PendingPayload =
  | { kind: 'checkout'; items: CheckoutItem[] }
  | { kind: 'payment'; orderId: string; request: PaymentRequest }
  | { kind: 'void'; orderId: string; paymentId: string; reason: string }
  | { kind: 'cancel'; orderId: string; reason: string }
  | { kind: 'refund'; orderId: string; request: RefundRequest }
  | { kind: 'restock'; sku: string; request: RestockRequest }
  | { kind: 'adjust'; sku: string; request: AdjustmentRequest };

export type PendingKind = PendingPayload['kind'];

export interface PendingAction {
  readonly v: 1;
  readonly key: string;
  readonly payload: PendingPayload;
  /** Epoch ms when the action was created. */
  readonly createdAt: number;
  /** Number of sends with this key so far (0 = persisted but never confirmed sent). */
  readonly sends: number;
  /** HTTP status of the last ambiguous send (0 = network/timeout), or null. */
  readonly lastStatus: number | null;
  /** Problem `code` of the last ambiguous send, if any. */
  readonly lastCode: string | null;
}

export function newPendingAction(
  payload: PendingPayload,
  gen: KeyGenerator = defaultKeyGenerator,
  nowMs: number = Date.now(),
): PendingAction {
  return {
    v: 1,
    key: gen(),
    payload,
    createdAt: nowMs,
    sends: 0,
    lastStatus: null,
    lastCode: null,
  };
}

export type SendResolution =
  /** Definitive: drop the pending action (the next action gets a new key). */
  | { settled: true; outcome: OutcomeKind }
  /** Ambiguous: keep (and persist) the updated action; retry with the same key. */
  | { settled: false; next: PendingAction };

/** Apply the outcome of one send to the pending action. */
export function resolveSend(
  p: PendingAction,
  status: number,
  code: string | null = null,
): SendResolution {
  const outcome = classifyOutcome(status);
  if (isDefinitive(outcome)) return { settled: true, outcome };
  return { settled: false, next: { ...p, sends: p.sends + 1, lastStatus: status, lastCode: code } };
}

/** Automatic retries after ambiguous outcomes stop after this many sends; then it's manual. */
export const MAX_AUTO_SENDS = 4;

/** Delay before the next automatic retry, or null when retries should be manual from now on. */
export function autoRetryDelay(
  p: PendingAction,
  maxSends = MAX_AUTO_SENDS,
  policy: BackoffPolicy = RETRY_BACKOFF,
): number | null {
  if (p.sends === 0 || p.sends >= maxSends) return null;
  return backoffDelay(p.sends, policy);
}

/** True when the same logical action is described (used to refuse a different payload). */
export function samePayload(a: PendingPayload, b: PendingPayload): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

// ---------------------------------------------------------------- (de)serialisation

export function encodePending(p: PendingAction): string {
  return JSON.stringify(p);
}

const isObj = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);
const isStr = (v: unknown): v is string => typeof v === 'string' && v.length > 0;
const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);

function validPayload(v: unknown): v is PendingPayload {
  if (!isObj(v)) return false;
  switch (v.kind) {
    case 'checkout':
      return (
        Array.isArray(v.items) &&
        v.items.length > 0 &&
        v.items.every((i) => isObj(i) && isStr(i.sku) && isNum(i.quantity))
      );
    case 'payment': {
      const r = v.request;
      if (!isStr(v.orderId) || !isObj(r) || !isNum(r.amount)) return false;
      if (r.method === 'CASH') return isNum(r.tendered);
      if (r.method === 'CARD') return isStr(r.reference);
      return false;
    }
    case 'void':
      return isStr(v.orderId) && isStr(v.paymentId) && isStr(v.reason);
    case 'cancel':
      return isStr(v.orderId) && isStr(v.reason);
    case 'restock': {
      const r = v.request;
      return (
        isStr(v.sku) &&
        isObj(r) &&
        isNum(r.quantity) &&
        (r.note === undefined || typeof r.note === 'string')
      );
    }
    case 'adjust': {
      const r = v.request;
      return (
        isStr(v.sku) &&
        isObj(r) &&
        isNum(r.delta) &&
        (r.reason === 'WASTE' || r.reason === 'DAMAGE' || r.reason === 'COUNT_CORRECTION') &&
        (r.note === undefined || typeof r.note === 'string')
      );
    }
    case 'refund': {
      const r = v.request;
      return (
        isStr(v.orderId) &&
        isObj(r) &&
        Array.isArray(r.lines) &&
        r.lines.every((l) => isObj(l) && isNum(l.lineNo) && isNum(l.quantity)) &&
        typeof r.restock === 'boolean' &&
        (r.method === 'CASH' || r.method === 'CARD') &&
        isStr(r.reason)
      );
    }
    default:
      return false;
  }
}

/** Parse a stored pending action; anything malformed or from another version yields null. */
export function decodePending(raw: string | null): PendingAction | null {
  if (!raw) return null;
  let v: unknown;
  try {
    v = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!isObj(v) || v.v !== 1 || !isStr(v.key) || v.key.length > 128) return null;
  if (!isNum(v.createdAt) || !isNum(v.sends) || !validPayload(v.payload)) return null;
  return {
    v: 1,
    key: v.key,
    payload: v.payload,
    createdAt: v.createdAt,
    sends: v.sends,
    lastStatus: isNum(v.lastStatus) ? v.lastStatus : null,
    lastCode: typeof v.lastCode === 'string' ? v.lastCode : null,
  };
}

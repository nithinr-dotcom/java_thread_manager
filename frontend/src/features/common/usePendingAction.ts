import { useCallback, useEffect, useRef, useState } from 'react';
import type { ApiFailure, ApiResult } from '../../api/client';
import {
  adjustStock,
  cancelOrder,
  checkout,
  payOrder,
  refundOrder,
  restock,
  voidPayment,
} from '../../api/endpoints';
import {
  autoRetryDelay,
  decodePending,
  encodePending,
  newPendingAction,
  resolveSend,
  type PendingAction,
  type PendingPayload,
} from '../../domain/idempotency';
import { loadRaw, removeKey, saveString, STORAGE_KEYS } from '../../shared/storage';

/** Sends a pending action with its (stable) Idempotency-Key. */
export function executePending(p: PendingAction): Promise<ApiResult<unknown>> {
  const pl = p.payload;
  switch (pl.kind) {
    case 'checkout':
      return checkout(p.key, { items: pl.items });
    case 'payment':
      return payOrder(pl.orderId, p.key, pl.request);
    case 'void':
      return voidPayment(pl.orderId, pl.paymentId, p.key, { reason: pl.reason });
    case 'cancel':
      return cancelOrder(pl.orderId, p.key, { reason: pl.reason });
    case 'refund':
      return refundOrder(pl.orderId, p.key, pl.request);
    case 'restock':
      return restock(pl.sku, p.key, pl.request);
    case 'adjust':
      return adjustStock(pl.sku, p.key, pl.request);
  }
}

export interface PendingController {
  /** The action whose outcome is not known yet (persisted), or null. */
  pending: PendingAction | null;
  /** True when `pending` was found in storage (previous session) and not re-sent yet. */
  recovered: boolean;
  busy: boolean;
  /** Last ambiguous failure (network / timeout / 5xx / 503 BUSY_RETRY / 408 / 429). */
  lastFailure: ApiFailure | null;
  /** Delay of the scheduled automatic retry, or null when retrying is manual. */
  retryInMs: number | null;
  /** Persist then send a new action. Refused (returns false) while another one is pending. */
  start: (payload: PendingPayload) => Promise<boolean>;
  /** Re-send the pending action with the SAME key. */
  retry: () => Promise<void>;
  /** Forget the pending action (the next action gets a new key). */
  discard: () => void;
}

export type SettledHandler = (payload: PendingPayload, result: ApiResult<unknown>) => void;

/**
 * Durable idempotent actions. The action (payload + Idempotency-Key) is written to storage BEFORE
 * the request is sent, kept across ambiguous outcomes (auto-retried with backoff a few times, then
 * manual) and removed only after a definitive outcome, when `onSettled` is called.
 */
export function usePendingAction(slot: string, onSettled: SettledHandler): PendingController {
  const storageKey = STORAGE_KEYS.pending(slot);
  const [initial] = useState(() => decodePending(loadRaw(storageKey)));
  const [pending, setPending] = useState<PendingAction | null>(initial);
  const [recovered, setRecovered] = useState(initial !== null);
  const [busy, setBusy] = useState(false);
  const [lastFailure, setLastFailure] = useState<ApiFailure | null>(null);
  const [retryInMs, setRetryInMs] = useState<number | null>(null);

  const pendingRef = useRef(pending);
  const inFlight = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const mounted = useRef(true);
  const onSettledRef = useRef(onSettled);
  onSettledRef.current = onSettled;

  const clearTimer = () => {
    if (timer.current !== null) clearTimeout(timer.current);
    timer.current = null;
  };

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      clearTimer();
    };
  }, []);

  const send = useCallback(
    async (p: PendingAction): Promise<void> => {
      if (inFlight.current) return;
      inFlight.current = true;
      clearTimer();
      setBusy(true);
      setRecovered(false);
      setRetryInMs(null);
      const result = await executePending(p);
      inFlight.current = false;
      const res = resolveSend(p, result.status, result.ok ? null : (result.problem.code ?? null));
      // Storage is updated even if the screen went away, so a later mount sees the truth.
      if (res.settled) removeKey(storageKey);
      else saveString(storageKey, encodePending(res.next));
      if (!mounted.current) return;
      setBusy(false);
      if (res.settled) {
        pendingRef.current = null;
        setPending(null);
        setLastFailure(null);
        onSettledRef.current(p.payload, result);
        return;
      }
      pendingRef.current = res.next;
      setPending(res.next);
      setLastFailure(result.ok ? null : result);
      const delay = autoRetryDelay(res.next);
      if (delay !== null) {
        setRetryInMs(delay);
        timer.current = setTimeout(() => void send(res.next), delay);
      }
    },
    [storageKey],
  );

  const start = useCallback(
    async (payload: PendingPayload): Promise<boolean> => {
      if (pendingRef.current || inFlight.current) return false;
      const p = newPendingAction(payload);
      saveString(storageKey, encodePending(p)); // BEFORE sending
      pendingRef.current = p;
      setPending(p);
      await send(p);
      return true;
    },
    [send, storageKey],
  );

  const retry = useCallback(async () => {
    const p = pendingRef.current;
    if (p) await send(p);
  }, [send]);

  const discard = useCallback(() => {
    clearTimer();
    removeKey(storageKey);
    pendingRef.current = null;
    setPending(null);
    setRecovered(false);
    setLastFailure(null);
    setRetryInMs(null);
  }, [storageKey]);

  return { pending, recovered, busy, lastFailure, retryInMs, start, retry, discard };
}

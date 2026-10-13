import { backoffDelay, STREAM_BACKOFF, type BackoffPolicy } from '../domain/backoff';
import { createSseParser } from '../domain/sse';
import { authHeaders, notifyUnauthorized, type FetchLike } from './client';
import { kitchenStreamUrl } from './endpoints';
import type { KitchenTicket } from './types';

export type StreamState =
  /** Connecting (first time or after a backoff). */
  | 'connecting'
  /** Response headers received; events are flowing. */
  | 'open'
  /** The connection ended or failed; a reconnect is scheduled. */
  | 'retrying'
  /** Stopped for good (aborted, or 401/403). */
  | 'closed';

export interface StreamStatus {
  state: StreamState;
  /** Consecutive failed connection attempts (reset once a connection opens). */
  failures: number;
  /** HTTP status of the last failure (0 = network), if any. */
  lastStatus: number | null;
  /** Delay before the next reconnect, when state is 'retrying'. */
  retryInMs: number | null;
}

export interface StreamOptions {
  onStatus?: (s: StreamStatus) => void;
  fetchImpl?: FetchLike;
  backoff?: BackoffPolicy;
  /** Injectable timer for tests. */
  sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
}

const defaultSleep = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve) => {
    if (signal.aborted) return resolve();
    const t = setTimeout(done, ms);
    function done() {
      clearTimeout(t);
      signal.removeEventListener('abort', done);
      resolve();
    }
    signal.addEventListener('abort', done, { once: true });
  });

/**
 * Consumes `GET /api/v1/kitchen/stream` with `fetch` + `ReadableStream` (EventSource cannot send
 * the Authorization header). Calls `onTicket` for every `event: ticket` frame, reconnects with
 * exponential backoff when the stream ends or fails, and resolves once `signal` aborts.
 * A 401/403 stops the loop (401 also signs the device out via `notifyUnauthorized`).
 */
export async function streamKitchen(
  station: string | null,
  onTicket: (t: KitchenTicket) => void,
  signal: AbortSignal,
  opts: StreamOptions = {},
): Promise<void> {
  const fetchImpl: FetchLike = opts.fetchImpl ?? ((i, init) => fetch(i, init));
  const sleep = opts.sleep ?? defaultSleep;
  const policy = opts.backoff ?? STREAM_BACKOFF;
  let failures = 0;
  let lastStatus: number | null = null;
  const report = (state: StreamState, retryInMs: number | null = null) =>
    opts.onStatus?.({ state, failures, lastStatus, retryInMs });

  while (!signal.aborted) {
    report('connecting');
    let opened = false;
    let httpStatus: number | null = null;
    try {
      const res = await fetchImpl(kitchenStreamUrl(station), {
        headers: { Accept: 'text/event-stream', 'Cache-Control': 'no-cache', ...authHeaders() },
        signal,
      });
      if (res.status === 401 || res.status === 403) {
        lastStatus = res.status;
        if (res.status === 401) notifyUnauthorized();
        report('closed');
        return;
      }
      if (!res.ok || !res.body || res.headers.get('X-Proxy-Error') !== null) {
        httpStatus = res.headers.get('X-Proxy-Error') !== null ? 0 : res.status;
        throw new Error(`stream HTTP ${res.status}`);
      }
      opened = true;
      failures = 0;
      lastStatus = null;
      report('open');
      const reader = res.body.getReader();
      const cancel = () => void reader.cancel().catch(() => undefined);
      signal.addEventListener('abort', cancel, { once: true });
      const decoder = new TextDecoder();
      const parser = createSseParser();
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done || signal.aborted) break;
          for (const ev of parser.push(decoder.decode(value, { stream: true }))) {
            if (ev.event !== 'ticket') continue;
            try {
              onTicket(JSON.parse(ev.data) as KitchenTicket);
            } catch {
              /* malformed frame: skip */
            }
          }
        }
      } finally {
        signal.removeEventListener('abort', cancel);
      }
    } catch {
      if (!opened) lastStatus = httpStatus ?? 0;
    }
    if (signal.aborted) break;
    if (!opened) failures++;
    const delay = backoffDelay(Math.max(1, failures), policy);
    report('retrying', delay);
    await sleep(delay, signal);
  }
  report('closed');
}

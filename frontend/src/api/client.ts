import type { Problem } from './types';

/** Synthetic status used when the request never got an HTTP response (network down, timeout). */
export const NETWORK_ERROR_STATUS = 0;
export const NETWORK_ERROR_CODE = 'NETWORK_ERROR';
export const TIMEOUT_CODE = 'TIMEOUT';
/** Set by the Vite dev/preview proxy (vite.config.ts) when the backend cannot be reached. */
export const PROXY_ERROR_HEADER = 'X-Proxy-Error';
/** Default request timeout. A timeout is an *ambiguous* outcome (status 0, code TIMEOUT). */
export const DEFAULT_TIMEOUT_MS = 10_000;

export interface ApiSuccess<T> {
  ok: true;
  status: number;
  headers: Headers;
  data: T;
  /** Wall-clock latency of the request in ms. */
  elapsedMs: number;
}

export interface ApiFailure {
  ok: false;
  /** HTTP status, or 0 when no response arrived (network error / timeout / abort). */
  status: number;
  headers: Headers | null;
  problem: Problem;
  elapsedMs: number;
}

export type ApiResult<T> = ApiSuccess<T> | ApiFailure;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  headers?: Record<string, string>;
  body?: unknown;
  signal?: AbortSignal;
  /** Overrides DEFAULT_TIMEOUT_MS; 0 disables the timeout. */
  timeoutMs?: number;
}

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

// ---------------------------------------------------------------- credentials

let apiKey: string | null = null;
const unauthorizedListeners = new Set<() => void>();

/** Set (or clear) the paired device's API key sent as `Authorization: Bearer <key>`. */
export function setApiKey(key: string | null): void {
  apiKey = key;
}

export function getApiKey(): string | null {
  return apiKey;
}

/** Called whenever any request gets a 401 (unknown / revoked key). Returns an unsubscribe fn. */
export function onUnauthorized(listener: () => void): () => void {
  unauthorizedListeners.add(listener);
  return () => unauthorizedListeners.delete(listener);
}

export function notifyUnauthorized(): void {
  for (const l of [...unauthorizedListeners]) l();
}

/** Headers every API call carries (Authorization when paired). */
export function authHeaders(key: string | null = apiKey): Record<string, string> {
  return key ? { Authorization: `Bearer ${key}` } : {};
}

// ---------------------------------------------------------------- helpers

const now = (): number =>
  typeof performance !== 'undefined' && typeof performance.now === 'function'
    ? performance.now()
    : Date.now();

function isProblemLike(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Normalises any error body into a Problem; never throws. */
export function toProblem(status: number, body: unknown, fallbackTitle: string): Problem {
  if (isProblemLike(body)) {
    const p: Problem = { ...body, status: typeof body.status === 'number' ? body.status : status };
    if (typeof p.title !== 'string') p.title = fallbackTitle;
    return p;
  }
  const detail = typeof body === 'string' && body.trim() !== '' ? body.trim() : undefined;
  return { status, title: fallbackTitle, ...(detail ? { detail } : {}) };
}

async function readBody(res: Response): Promise<unknown> {
  const text = await res.text();
  if (text === '') return undefined;
  const ct = res.headers.get('content-type') ?? '';
  if (ct.includes('json')) {
    try {
      return JSON.parse(text) as unknown;
    } catch {
      return text;
    }
  }
  return text;
}

/**
 * Links an internal AbortController to an optional caller signal and a timeout.
 * Avoids AbortSignal.any/timeout for wider browser support.
 */
export function abortScope(signal: AbortSignal | undefined, timeoutMs: number) {
  const controller = new AbortController();
  let timedOut = false;
  const onAbort = () => controller.abort();
  if (signal) {
    if (signal.aborted) controller.abort();
    else signal.addEventListener('abort', onAbort, { once: true });
  }
  const timer =
    timeoutMs > 0
      ? setTimeout(() => {
          timedOut = true;
          controller.abort();
        }, timeoutMs)
      : undefined;
  return {
    signal: controller.signal,
    timedOut: () => timedOut,
    dispose: () => {
      if (timer !== undefined) clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
    },
  };
}

function noResponse(code: string, title: string, detail: string, started: number): ApiFailure {
  return {
    ok: false,
    status: NETWORK_ERROR_STATUS,
    headers: null,
    problem: { status: NETWORK_ERROR_STATUS, code, title, detail },
    elapsedMs: now() - started,
  };
}

/**
 * Fetch wrapper. Never throws on HTTP errors, network failures or timeouts — always resolves to an
 * ApiResult. Errors are parsed as RFC 7807 problem+json (including the backend's `code`).
 * Adds `Authorization: Bearer <key>` when a device is paired, and reports 401s to listeners.
 */
export async function request<T>(
  url: string,
  opts: RequestOptions = {},
  fetchImpl: FetchLike = (input, init) => fetch(input, init),
): Promise<ApiResult<T>> {
  const headers: Record<string, string> = {
    Accept: 'application/json, application/problem+json',
    ...authHeaders(),
    ...opts.headers,
  };
  const scope = abortScope(opts.signal, opts.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  const init: RequestInit = { method: opts.method ?? 'GET', headers, signal: scope.signal };
  if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(opts.body);
  }
  const started = now();
  let res: Response;
  let body: unknown;
  try {
    res = await fetchImpl(url, init);
    try {
      body = await readBody(res);
    } catch (err) {
      if (scope.timedOut()) throw err;
      body = undefined;
    }
  } catch (err) {
    scope.dispose();
    if (scope.timedOut()) {
      return noResponse(
        TIMEOUT_CODE,
        'Request timed out',
        `No response within ${opts.timeoutMs ?? DEFAULT_TIMEOUT_MS} ms — outcome unknown`,
        started,
      );
    }
    return noResponse(
      NETWORK_ERROR_CODE,
      'Backend unreachable',
      err instanceof Error ? err.message : String(err),
      started,
    );
  }
  scope.dispose();
  const elapsedMs = now() - started;
  if (res.headers.get(PROXY_ERROR_HEADER) !== null) {
    // Dev/preview proxy could not reach the backend: same as a network error.
    return {
      ok: false,
      status: NETWORK_ERROR_STATUS,
      headers: res.headers,
      problem: {
        ...toProblem(res.status, body, 'Backend unreachable'),
        status: NETWORK_ERROR_STATUS,
        code: NETWORK_ERROR_CODE,
      },
      elapsedMs,
    };
  }
  if (res.ok) {
    return { ok: true, status: res.status, headers: res.headers, data: body as T, elapsedMs };
  }
  if (res.status === 401) notifyUnauthorized();
  return {
    ok: false,
    status: res.status,
    headers: res.headers,
    problem: toProblem(res.status, body, res.statusText || `HTTP ${res.status}`),
    elapsedMs,
  };
}

/** True when the failure means no HTTP response arrived (unreachable or timeout). */
export function isNetworkError(r: ApiResult<unknown>): boolean {
  return !r.ok && r.status === NETWORK_ERROR_STATUS;
}

/** `Idempotent-Replayed` header as boolean, or null when absent. */
export function replayedHeader(headers: Headers | null): boolean | null {
  const v = headers?.get('Idempotent-Replayed');
  if (v == null) return null;
  return v.trim().toLowerCase() === 'true';
}

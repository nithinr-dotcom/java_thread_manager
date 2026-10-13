import { vi } from 'vitest';

export interface FakeRequest {
  method: string;
  /** Full URL including the query string. */
  url: string;
  /** Path without the query string. */
  path: string;
  query: URLSearchParams;
  headers: Record<string, string>;
  body: unknown;
  signal: AbortSignal | null;
}

export type Handler = (req: FakeRequest) => Response | Promise<Response>;
export type Route = [method: string, path: string | RegExp, handler: Handler];

export const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json',
      ...headers,
    },
  });

export const problem = (status: number, code: string, extra: Record<string, unknown> = {}) =>
  json(status, { type: 'about:blank', title: code, status, code, ...extra });

/**
 * A routed `fetch` mock. Unmatched requests answer 404; `/actuator/health` answers UP unless
 * overridden. Every call is recorded in `requests`.
 */
export function fakeServer(routes: Route[]) {
  const requests: FakeRequest[] = [];
  const all: Route[] = [...routes, ['GET', '/actuator/health', () => json(200, { status: 'UP' })]];
  const fetchMock = vi.fn(async (input: string, init: RequestInit = {}) => {
    const u = new URL(input, 'http://localhost');
    let body: unknown = undefined;
    if (typeof init.body === 'string') {
      try {
        body = JSON.parse(init.body);
      } catch {
        body = init.body;
      }
    }
    const req: FakeRequest = {
      method: init.method ?? 'GET',
      url: input,
      path: u.pathname,
      query: u.searchParams,
      headers: (init.headers ?? {}) as Record<string, string>,
      body,
      signal: init.signal ?? null,
    };
    requests.push(req);
    const route = all.find(
      ([m, p]) => m === req.method && (typeof p === 'string' ? p === req.path : p.test(req.path)),
    );
    if (!route) return json(404, { status: 404, title: 'Not found' });
    return route[2](req);
  });
  return {
    fetch: fetchMock,
    requests,
    /** Requests with this method + path. */
    calls: (method: string, path: string | RegExp) =>
      requests.filter(
        (r) =>
          r.method === method && (typeof path === 'string' ? r.path === path : path.test(r.path)),
      ),
  };
}

/** An SSE response that emits `frames` and then stays open until the request is aborted. */
export function sseOpen(frames: string[], signal: AbortSignal | null): Response {
  const enc = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      for (const f of frames) c.enqueue(enc.encode(f));
      signal?.addEventListener('abort', () => {
        try {
          c.close();
        } catch {
          /* already closed */
        }
      });
    },
  });
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

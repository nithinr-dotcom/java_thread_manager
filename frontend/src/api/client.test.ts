import { describe, expect, it, vi } from 'vitest';
import {
  isNetworkError,
  onUnauthorized,
  replayedHeader,
  request,
  setApiKey,
  toProblem,
} from './client';
import { checkout, getMe, listKitchenTickets, listOrders } from './endpoints';

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });

describe('request', () => {
  it('returns ok result with status, headers and parsed data', async () => {
    const f = vi.fn().mockResolvedValue(json(200, [{ sku: 'A' }], { 'X-Foo': 'bar' }));
    const r = await request<{ sku: string }[]>('/api/v1/catalog', {}, f);
    expect(r.ok).toBe(true);
    if (!r.ok) throw new Error('unreachable');
    expect(r.status).toBe(200);
    expect(r.headers.get('X-Foo')).toBe('bar');
    expect(r.data).toEqual([{ sku: 'A' }]);
    expect(r.elapsedMs).toBeGreaterThanOrEqual(0);
  });

  it('parses RFC 7807 problem+json including code and extras', async () => {
    const body = {
      type: 'about:blank',
      title: 'Out of stock',
      status: 409,
      detail: 'Not enough BURGER',
      code: 'OUT_OF_STOCK',
      sku: 'BURGER',
      requested: 3,
    };
    const f = vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), {
        status: 409,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    );
    const r = await request('/x', {}, f);
    expect(r.ok).toBe(false);
    if (r.ok) throw new Error('unreachable');
    expect(r.status).toBe(409);
    expect(r.problem.code).toBe('OUT_OF_STOCK');
    expect(r.problem.sku).toBe('BURGER');
    expect(r.problem.requested).toBe(3);
    expect(r.problem.detail).toBe('Not enough BURGER');
  });

  it('never throws on network errors; reports status 0', async () => {
    const f = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    const r = await request('/x', {}, f);
    expect(r.ok).toBe(false);
    expect(isNetworkError(r)).toBe(true);
    if (r.ok) throw new Error('unreachable');
    expect(r.problem.code).toBe('NETWORK_ERROR');
    expect(r.problem.detail).toBe('Failed to fetch');
  });

  it('treats a dev-proxy 502 with X-Proxy-Error as a network error', async () => {
    const f = vi
      .fn()
      .mockResolvedValue(
        json(
          502,
          { title: 'Backend unreachable', status: 502, detail: 'ECONNREFUSED' },
          { 'X-Proxy-Error': '1' },
        ),
      );
    const r = await request('/x', {}, f);
    expect(isNetworkError(r)).toBe(true);
    if (r.ok) throw new Error('unreachable');
    expect(r.problem.code).toBe('NETWORK_ERROR');
    expect(r.problem.detail).toBe('ECONNREFUSED');
  });

  it('handles non-JSON error bodies', async () => {
    const f = vi
      .fn()
      .mockResolvedValue(new Response('Bad Gateway', { status: 502, statusText: 'Bad Gateway' }));
    const r = await request('/x', {}, f);
    if (r.ok) throw new Error('unreachable');
    expect(r.status).toBe(502);
    expect(r.problem.title).toBe('Bad Gateway');
    expect(r.problem.detail).toBe('Bad Gateway');
    expect(isNetworkError(r)).toBe(false);
  });

  it('handles malformed JSON and empty bodies', async () => {
    const bad = vi
      .fn()
      .mockResolvedValue(
        new Response('{oops', { status: 500, headers: { 'Content-Type': 'application/json' } }),
      );
    const r1 = await request('/x', {}, bad);
    if (r1.ok) throw new Error('unreachable');
    expect(r1.problem.status).toBe(500);
    expect(r1.problem.detail).toBe('{oops');

    const empty = vi.fn().mockResolvedValue(new Response(null, { status: 404 }));
    const r2 = await request('/x', {}, empty);
    if (r2.ok) throw new Error('unreachable');
    expect(r2.problem.status).toBe(404);
    expect(r2.problem.title).toBeTruthy();
  });

  it('sends JSON body, Idempotency-Key and the bearer key', async () => {
    setApiKey('sf_demo_pos');
    const f = vi.fn().mockResolvedValue(json(201, { id: '1' }, { 'Idempotent-Replayed': 'false' }));
    await checkout('key-1', { items: [{ sku: 'A', quantity: 2 }] }, { fetchImpl: f });
    setApiKey(null);
    const [url, init] = f.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/orders');
    expect(init.method).toBe('POST');
    const h = init.headers as Record<string, string>;
    expect(h.Authorization).toBe('Bearer sf_demo_pos');
    expect(h['Idempotency-Key']).toBe('key-1');
    expect(h['Content-Type']).toBe('application/json');
    expect(JSON.parse(init.body as string)).toEqual({ items: [{ sku: 'A', quantity: 2 }] });
  });

  it('omits Authorization when not paired and lets getMe validate a candidate key', async () => {
    const f = vi.fn().mockResolvedValue(json(200, {}));
    await request('/x', {}, f);
    expect((f.mock.calls[0]?.[1] as RequestInit).headers).not.toHaveProperty('Authorization');
    await getMe('sf_candidate', { fetchImpl: f });
    expect(
      ((f.mock.calls[1]?.[1] as RequestInit).headers as Record<string, string>).Authorization,
    ).toBe('Bearer sf_candidate');
  });

  it('builds query strings', async () => {
    const f = vi.fn().mockResolvedValue(json(200, []));
    await listOrders({ limit: 5, status: 'PAID' }, { fetchImpl: f });
    await listOrders({}, { fetchImpl: f });
    await listKitchenTickets({ active: true, station: 'GRILL' }, { fetchImpl: f });
    expect(f.mock.calls.map((c) => c[0])).toEqual([
      '/api/v1/orders?limit=5&status=PAID',
      '/api/v1/orders?limit=20',
      '/api/v1/kitchen/tickets?limit=50&active=true&station=GRILL',
    ]);
  });

  it('reports a timeout as an ambiguous status-0 TIMEOUT failure', async () => {
    const f = vi.fn(
      (_url: string, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          );
        }),
    );
    const r = await request('/slow', { timeoutMs: 20 }, f);
    expect(r.ok).toBe(false);
    if (r.ok) throw new Error('unreachable');
    expect(r.status).toBe(0);
    expect(r.problem.code).toBe('TIMEOUT');
    expect(isNetworkError(r)).toBe(true);
  });

  it('reports a caller abort as a network error, not a timeout', async () => {
    const ac = new AbortController();
    const f = vi.fn(
      (_url: string, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          );
        }),
    );
    const p = request('/x', { signal: ac.signal }, f);
    ac.abort();
    const r = await p;
    if (r.ok) throw new Error('unreachable');
    expect(r.problem.code).toBe('NETWORK_ERROR');
  });

  it('notifies listeners on 401', async () => {
    const listener = vi.fn();
    const off = onUnauthorized(listener);
    const f = vi.fn().mockResolvedValue(json(401, { status: 401, code: 'UNAUTHENTICATED' }));
    const r = await request('/x', {}, f);
    off();
    expect(r.status).toBe(401);
    expect(listener).toHaveBeenCalledTimes(1);
  });
});

describe('toProblem', () => {
  it('fills status from HTTP status when body lacks a numeric status', () => {
    expect(toProblem(503, { status: 'DOWN' }, 'Service Unavailable').status).toBe(503);
  });
});

describe('replayedHeader', () => {
  it('parses true/false/absent', () => {
    expect(replayedHeader(new Headers({ 'Idempotent-Replayed': 'true' }))).toBe(true);
    expect(replayedHeader(new Headers({ 'Idempotent-Replayed': 'False' }))).toBe(false);
    expect(replayedHeader(new Headers())).toBeNull();
    expect(replayedHeader(null)).toBeNull();
  });
});

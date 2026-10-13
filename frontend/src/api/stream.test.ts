import { describe, expect, it, vi } from 'vitest';
import { onUnauthorized, setApiKey } from './client';
import { streamKitchen, type StreamStatus } from './stream';
import type { KitchenTicket } from './types';

const ticket = (id: string): KitchenTicket => ({
  id,
  orderId: 'o',
  orderNumber: 'ORD-1',
  terminalId: 'pos-1',
  status: 'QUEUED',
  receivedAt: '2026-10-13T12:00:00Z',
  updatedAt: '2026-10-13T12:00:00Z',
  items: [],
});

function sseResponse(chunks: string[]): Response {
  const enc = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      for (const ch of chunks) c.enqueue(enc.encode(ch));
      c.close();
    },
  });
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

describe('streamKitchen', () => {
  it('parses ticket frames split across chunks, sends auth, reconnects with backoff', async () => {
    setApiKey('sf_test');
    const ac = new AbortController();
    const frame = `event: ticket\ndata: ${JSON.stringify(ticket('t1'))}\n\n`;
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(sseResponse([': ping\n\n', frame.slice(0, 10), frame.slice(10)]))
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(new Response('busy', { status: 503 }))
      .mockResolvedValue(sseResponse([`event: ticket\ndata: ${JSON.stringify(ticket('t2'))}\n\n`]));
    const got: string[] = [];
    const statuses: StreamStatus[] = [];
    const delays: number[] = [];
    await streamKitchen('GRILL', (t) => got.push(t.id), ac.signal, {
      fetchImpl,
      onStatus: (s) => statuses.push(s),
      backoff: { baseMs: 100, maxMs: 1000 },
      sleep: async (ms) => {
        delays.push(ms);
        if (delays.length === 4) ac.abort();
      },
    });
    setApiKey(null);
    expect(got).toEqual(['t1', 't2']);
    const [url, init] = fetchImpl.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/kitchen/stream?station=GRILL');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer sf_test');
    // after a clean end: failures 0 → base delay; network error: 1; 503: 2; then clean again.
    expect(delays).toEqual([100, 100, 200, 100]);
    expect(statuses.some((s) => s.state === 'retrying' && s.lastStatus === 503)).toBe(true);
    expect(statuses.at(-1)?.state).toBe('closed');
  });

  it('stops and notifies on 401', async () => {
    const listener = vi.fn();
    const off = onUnauthorized(listener);
    const fetchImpl = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
    await streamKitchen(null, () => undefined, new AbortController().signal, { fetchImpl });
    off();
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(fetchImpl.mock.calls[0]?.[0]).toBe('/api/v1/kitchen/stream');
    expect(listener).toHaveBeenCalledTimes(1);
  });
});

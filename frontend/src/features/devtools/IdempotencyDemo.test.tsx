import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { catalog, me, placedOrder } from '../../test/fixtures';
import { fakeServer, json, problem } from '../../test/fakeServer';
import { renderAs } from '../../test/render';
import { IdempotencyDemo } from './IdempotencyDemo';

/** Minimal fake of the backend's idempotent checkout semantics. */
function idempotentBackend() {
  const committed = new Map<string, string>(); // key -> body
  return fakeServer([
    ['GET', '/api/v1/catalog', () => json(200, catalog)],
    [
      'POST',
      '/api/v1/orders',
      (req) => {
        const key = req.headers['Idempotency-Key'] ?? '';
        const body = JSON.stringify(req.body);
        const prev = committed.get(key);
        if (prev === undefined) {
          committed.set(key, body);
          return json(201, placedOrder(), { 'Idempotent-Replayed': 'false' });
        }
        if (prev === body) return json(200, placedOrder(), { 'Idempotent-Replayed': 'true' });
        return problem(422, 'IDEMPOTENCY_KEY_REUSED');
      },
    ],
  ]);
}

describe('IdempotencyDemo (developer tool)', () => {
  it('checks out (201), replays with the same key (200), rejects a different basket (422), rotates on new key', async () => {
    const server = idempotentBackend();
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<IdempotencyDemo />, me('MANAGER'));

    await screen.findByRole('option', { name: 'BURGER-CLASSIC' });
    const key = screen.getByTestId('idem-key').textContent ?? '';
    expect(key).toMatch(/^[0-9a-f-]{36}$/);

    await user.click(screen.getByRole('button', { name: 'Checkout' }));
    const result = await screen.findByTestId('result');
    await waitFor(() => expect(within(result).getByText('HTTP 201')).toBeInTheDocument());
    expect(within(result).getByText('Idempotent-Replayed: false')).toBeInTheDocument();
    expect(within(result).getByTestId('order-number')).toHaveTextContent(
      'ORD-HOSP01-20261013-0001',
    );

    await user.click(screen.getByRole('button', { name: 'Retry same key' }));
    await waitFor(() =>
      expect(within(screen.getByTestId('result')).getByText('HTTP 200')).toBeInTheDocument(),
    );
    expect(
      within(screen.getByTestId('result')).getByText('Idempotent-Replayed: true'),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Same key, different basket' }));
    await waitFor(() =>
      expect(within(screen.getByTestId('result')).getByText('HTTP 422')).toBeInTheDocument(),
    );
    expect(screen.getByText('IDEMPOTENCY_KEY_REUSED', { selector: 'code' })).toBeInTheDocument();

    const posts = server.calls('POST', '/api/v1/orders');
    expect(posts).toHaveLength(3);
    for (const p of posts) expect(p.headers['Idempotency-Key']).toBe(key);

    await user.click(screen.getByRole('button', { name: 'New key' }));
    expect(screen.queryByTestId('result')).not.toBeInTheDocument();
    expect(screen.getByTestId('idem-key').textContent).not.toBe(key);
  });

  it('keeps the key after a network error', async () => {
    const server = idempotentBackend();
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<IdempotencyDemo />, me('MANAGER'));
    await screen.findByRole('option', { name: 'BURGER-CLASSIC' });
    const key = screen.getByTestId('idem-key').textContent;
    server.fetch.mockImplementation(async () => {
      throw new TypeError('Failed to fetch');
    });
    await user.click(screen.getByRole('button', { name: 'Checkout' }));
    await within(await screen.findByTestId('result')).findByText('NETWORK_ERROR');
    await user.click(screen.getByRole('button', { name: 'Checkout' }));
    expect(screen.getByTestId('idem-key').textContent).toBe(key);
  });
});

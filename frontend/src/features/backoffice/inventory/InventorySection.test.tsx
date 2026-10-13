import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { catalog, me } from '../../../test/fixtures';
import { fakeServer, json, problem } from '../../../test/fakeServer';
import { renderAs } from '../../../test/render';
import { InventorySection } from './InventorySection';

const routes = (restockAnswers: (() => Response)[]) => {
  let i = 0;
  return fakeServer([
    ['GET', '/api/v1/catalog', () => json(200, catalog)],
    ['GET', '/api/v1/inventory/BURGER-CLASSIC/movements', () => json(200, [])],
    [
      'POST',
      '/api/v1/inventory/BURGER-CLASSIC/restock',
      () => (restockAnswers[Math.min(i++, restockAnswers.length - 1)] as () => Response)(),
    ],
    [
      'POST',
      '/api/v1/inventory/BURGER-CLASSIC/adjustments',
      () => json(200, { sku: 'BURGER-CLASSIC', onHand: 49 }, { 'Idempotent-Replayed': 'false' }),
    ],
  ]);
};

describe('InventorySection', () => {
  it('restocks idempotently: ambiguous outcome keeps the key, retry replays it', async () => {
    const server = routes([
      () => problem(503, 'BUSY_RETRY'),
      () => json(200, { sku: 'BURGER-CLASSIC', onHand: 60 }, { 'Idempotent-Replayed': 'true' }),
    ]);
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<InventorySection />, me('MANAGER'));

    const qty = await screen.findByLabelText('Quantity');
    await user.clear(qty);
    await user.type(qty, '10');
    await user.type(screen.getAllByLabelText('Note')[0]!, 'Delivery #4411');
    await user.click(screen.getByRole('button', { name: 'Restock' }));

    const banner = await screen.findByTestId('pending-banner');
    expect(banner).toHaveTextContent(/Unfinished restock/);
    expect(localStorage.getItem('edge.pending.inventory')).toContain('"kind":"restock"');
    await user.click(within(banner).getByRole('button', { name: 'Retry' }));

    expect(await screen.findByText('BURGER-CLASSIC: 60 on hand now.')).toBeInTheDocument();
    const posts = server.calls('POST', '/api/v1/inventory/BURGER-CLASSIC/restock');
    expect(posts.length).toBeGreaterThanOrEqual(2);
    expect(new Set(posts.map((p) => p.headers['Idempotency-Key'])).size).toBe(1);
    expect(posts[0]?.body).toEqual({ quantity: 10, note: 'Delivery #4411' });
    expect(localStorage.getItem('edge.pending.inventory')).toBeNull();
  });

  it('sends adjustments with a fresh Idempotency-Key each time', async () => {
    const server = routes([() => json(200, { sku: 'BURGER-CLASSIC', onHand: 60 })]);
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<InventorySection />, me('MANAGER'));
    const apply = await screen.findByRole('button', { name: 'Apply adjustment' });
    await user.click(apply);
    expect(await screen.findByText('BURGER-CLASSIC: 49 on hand now.')).toBeInTheDocument();
    await user.click(apply);
    await screen.findByText('BURGER-CLASSIC: 49 on hand now.');
    const posts = server.calls('POST', '/api/v1/inventory/BURGER-CLASSIC/adjustments');
    await vi.waitFor(() => expect(posts).toHaveLength(2));
    expect(posts[0]?.body).toEqual({ delta: -1, reason: 'WASTE' });
    expect(posts[0]?.headers['Idempotency-Key']).not.toBe(posts[1]?.headers['Idempotency-Key']);
  });
});

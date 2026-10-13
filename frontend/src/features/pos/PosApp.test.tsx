import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { Order, PaymentRequest } from '../../api/types';
import { encodePending, newPendingAction } from '../../domain/idempotency';
import { cashPayment, catalog, me, placedOrder } from '../../test/fixtures';
import { fakeServer, json, problem } from '../../test/fakeServer';
import { renderAs } from '../../test/render';
import { PosApp } from './PosApp';

const paidOrder = (tendered: number, change: number): Order =>
  placedOrder({
    status: 'PAID',
    amountPaid: 24.68,
    amountDue: 0,
    payments: [cashPayment({ tendered, change })],
  });

function posServer(over: { checkout?: () => Response } = {}) {
  return fakeServer([
    ['GET', '/api/v1/catalog', () => json(200, catalog)],
    [
      'POST',
      '/api/v1/orders',
      over.checkout ?? (() => json(201, placedOrder(), { 'Idempotent-Replayed': 'false' })),
    ],
    [
      'POST',
      '/api/v1/orders/order-1/payments',
      (req) => {
        const body = req.body as PaymentRequest & { tendered: number };
        const order = paidOrder(body.tendered, 25.32);
        return json(201, { payment: order.payments[0], order });
      },
    ],
    ['GET', '/api/v1/orders/order-1', () => json(200, placedOrder())],
  ]);
}

describe('POS', () => {
  it('checkout → cash payment → receipt with server-computed change', async () => {
    const server = posServer();
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<PosApp />, me('POS'));

    // Out-of-stock products are disabled; categories are tabs.
    expect(await screen.findByRole('button', { name: 'Add Sparkling water' })).toBeDisabled();
    expect(screen.getByRole('tab', { name: 'Drinks' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Add Classic burger' }));
    await user.click(screen.getByRole('button', { name: 'One more Classic burger' }));
    expect(screen.getByText('Total calculated at checkout')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Checkout' }));

    // Payment screen shows server totals.
    expect(await screen.findByTestId('order-total')).toHaveTextContent('$24.68');
    expect(screen.getByTestId('amount-due')).toHaveTextContent('$24.68');
    const [checkoutReq] = server.calls('POST', '/api/v1/orders');
    expect(checkoutReq?.body).toEqual({ items: [{ sku: 'BURGER-CLASSIC', quantity: 2 }] });
    expect(checkoutReq?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(checkoutReq?.headers.Authorization).toBe('Bearer sf_test_pos');

    await user.click(screen.getByRole('button', { name: '$50.00' }));
    expect(screen.getByLabelText('Cash received')).toHaveValue('50.00');
    await user.click(screen.getByRole('button', { name: 'Take cash payment' }));

    const receipt = await screen.findByTestId('receipt');
    expect(within(receipt).getByTestId('receipt-total')).toHaveTextContent('$24.68');
    expect(within(receipt).getByTestId('receipt-change')).toHaveTextContent('$25.32');
    expect(within(receipt).getByText('Store HOSP01')).toBeInTheDocument();
    expect(screen.getByText('Change due: $25.32')).toBeInTheDocument();
    const [payReq] = server.calls('POST', '/api/v1/orders/order-1/payments');
    expect(payReq?.body).toEqual({ method: 'CASH', amount: 24.68, tendered: 50 });
    expect(payReq?.headers['Idempotency-Key']).not.toBe(checkoutReq?.headers['Idempotency-Key']);

    // Nothing left pending; new sale returns to the product grid.
    expect(localStorage.getItem('edge.pending.pos')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'New sale' }));
    expect(await screen.findByRole('button', { name: 'Add Classic burger' })).toBeEnabled();
    expect(localStorage.getItem('edge.pos.activeOrder')).toBeNull();
  });

  it('recovers an unfinished checkout after a reload and retries it with the SAME key', async () => {
    const pending = newPendingAction(
      { kind: 'checkout', items: [{ sku: 'BURGER-CLASSIC', quantity: 2 }] },
      () => 'key-from-before-reload',
    );
    localStorage.setItem('edge.pending.pos', encodePending(pending));
    const server = posServer({
      checkout: () => json(200, placedOrder(), { 'Idempotent-Replayed': 'true' }),
    });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<PosApp />, me('POS'));

    const banner = await screen.findByTestId('pending-banner');
    expect(banner).toHaveTextContent(/Unfinished checkout/);
    expect(server.calls('POST', '/api/v1/orders')).toHaveLength(0); // never re-sent silently
    // The checkout button is blocked while an action is unresolved.
    expect(screen.getByRole('button', { name: 'Checkout' })).toBeDisabled();

    await user.click(within(banner).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByTestId('order-total')).toHaveTextContent('$24.68');
    const posts = server.calls('POST', '/api/v1/orders');
    expect(posts).toHaveLength(1);
    expect(posts[0]?.headers['Idempotency-Key']).toBe('key-from-before-reload');
    expect(posts[0]?.body).toEqual({ items: [{ sku: 'BURGER-CLASSIC', quantity: 2 }] });
    expect(localStorage.getItem('edge.pending.pos')).toBeNull();
    expect(screen.queryByTestId('pending-banner')).not.toBeInTheDocument();
  });

  it('keeps the key after an ambiguous outcome and persists it before sending', async () => {
    let calls = 0;
    const server = posServer({
      checkout: () => {
        calls++;
        return calls === 1
          ? problem(503, 'BUSY_RETRY')
          : json(201, placedOrder(), { 'Idempotent-Replayed': 'false' });
      },
    });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<PosApp />, me('POS'));

    await user.click(await screen.findByRole('button', { name: 'Add Classic burger' }));
    await user.click(screen.getByRole('button', { name: 'Checkout' }));
    const banner = await screen.findByTestId('pending-banner');
    expect(banner).toHaveTextContent(/outcome unknown/);
    const stored = JSON.parse(localStorage.getItem('edge.pending.pos') ?? '{}') as {
      key: string;
      sends: number;
    };
    expect(stored.sends).toBe(1);

    await user.click(within(banner).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByTestId('order-total')).toBeInTheDocument();
    const keys = server.calls('POST', '/api/v1/orders').map((r) => r.headers['Idempotency-Key']);
    expect(keys).toHaveLength(2);
    expect(new Set(keys).size).toBe(1);
    expect(keys[0]).toBe(stored.key);
  });

  it('shows a business rejection and keeps the cart (definitive: key rotates)', async () => {
    const server = posServer({
      checkout: () => problem(409, 'OUT_OF_STOCK', { sku: 'BURGER-CLASSIC', requested: 1 }),
    });
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<PosApp />, me('POS'));
    await user.click(await screen.findByRole('button', { name: 'Add Classic burger' }));
    await user.click(screen.getByRole('button', { name: 'Checkout' }));
    expect(await screen.findByText(/Not enough stock for BURGER-CLASSIC/)).toBeInTheDocument();
    expect(screen.queryByTestId('pending-banner')).not.toBeInTheDocument();
    expect(localStorage.getItem('edge.pending.pos')).toBeNull();
    expect(screen.getByRole('button', { name: 'Checkout' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Checkout' }));
    await waitFor(() => expect(server.calls('POST', '/api/v1/orders')).toHaveLength(2));
    const [a, b] = server.calls('POST', '/api/v1/orders');
    expect(a?.headers['Idempotency-Key']).not.toBe(b?.headers['Idempotency-Key']);
  });

  it('disables checkout while the store server is unreachable', async () => {
    vi.stubGlobal('fetch', posServer().fetch);
    const user = userEvent.setup();
    renderAs(<PosApp />, me('POS'), { reach: 'unreachable' });
    await user.click(await screen.findByRole('button', { name: 'Add Classic burger' }));
    expect(screen.getByRole('alert')).toHaveTextContent(/checkout is disabled/);
    expect(screen.getByRole('button', { name: 'Checkout' })).toBeDisabled();
  });

  it('resumes the order being paid after a reload and can cancel it with a reason', async () => {
    localStorage.setItem('edge.pos.activeOrder', 'order-1');
    const cancelled = placedOrder({ status: 'CANCELLED' });
    const routes = fakeServer([
      ['GET', '/api/v1/catalog', () => json(200, catalog)],
      ['GET', '/api/v1/orders/order-1', () => json(200, placedOrder())],
      ['POST', '/api/v1/orders/order-1/cancel', () => json(200, cancelled)],
    ]);
    vi.stubGlobal('fetch', routes.fetch);
    const user = userEvent.setup();
    renderAs(<PosApp />, me('POS'));
    expect(await screen.findByTestId('amount-due')).toHaveTextContent('$24.68');
    await user.click(screen.getByRole('button', { name: 'Cancel order' }));
    await user.type(screen.getByLabelText('Reason for cancelling'), 'Customer left');
    await user.click(screen.getByRole('button', { name: 'Cancel this order' }));
    expect(await screen.findByText(/was cancelled/)).toBeInTheDocument();
    const [req] = routes.calls('POST', '/api/v1/orders/order-1/cancel');
    expect(req?.body).toEqual({ reason: 'Customer left' });
    expect(req?.headers['Idempotency-Key']).toBeTruthy();
    expect(localStorage.getItem('edge.pos.activeOrder')).toBeNull();
  });

  it('voids a payment of an abandoned split tender, after which the order can be cancelled', async () => {
    localStorage.setItem('edge.pos.activeOrder', 'order-1');
    const card = cashPayment({
      id: 'pay-card',
      method: 'CARD',
      amount: 10,
      tendered: null,
      change: null,
      reference: 'AUTH-1',
    });
    const partlyPaid = placedOrder({ amountPaid: 10, amountDue: 14.68, payments: [card] });
    const voided = placedOrder({
      payments: [{ ...card, voidedAt: '2026-10-13T12:01:00Z', voidReason: 'Card refused' }],
    });
    const server = fakeServer([
      ['GET', '/api/v1/catalog', () => json(200, catalog)],
      ['GET', '/api/v1/orders/order-1', () => json(200, partlyPaid)],
      ['POST', '/api/v1/orders/order-1/payments/pay-card/void', () => json(200, voided)],
    ]);
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<PosApp />, me('POS'));

    expect(await screen.findByTestId('amount-due')).toHaveTextContent('$14.68');
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument();
    expect(screen.getByText(/Void the payments above/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Void payment $10.00' }));
    await user.type(screen.getByLabelText(/Reason for voiding/), 'Card refused');
    await user.click(screen.getByRole('button', { name: 'Void this payment' }));

    expect(await screen.findByText('Payment voided. Amount due: $24.68.')).toBeInTheDocument();
    expect(screen.getByTestId('amount-due')).toHaveTextContent('$24.68');
    expect(screen.getByTestId('payment-pay-card')).toHaveClass('is-voided');
    expect(screen.getByRole('button', { name: 'Cancel order' })).toBeEnabled();
    const [req] = server.calls('POST', '/api/v1/orders/order-1/payments/pay-card/void');
    expect(req?.body).toEqual({ reason: 'Card refused' });
    expect(req?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(localStorage.getItem('edge.pending.pos')).toBeNull();
  });
});

import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { Order, Refund } from '../../../api/types';
import { cashPayment, me, placedOrder } from '../../../test/fixtures';
import { fakeServer, json } from '../../../test/fakeServer';
import { renderAs } from '../../../test/render';
import { OrderDetail } from './OrderDetail';

const paid: Order = placedOrder({
  status: 'PAID',
  amountPaid: 24.68,
  amountDue: 0,
  payments: [cashPayment()],
});

const refund: Refund = {
  id: 'refund-1',
  refundNumber: 'REF-HOSP01-20261013-0001',
  method: 'CASH',
  reason: 'Cold food',
  restocked: false,
  issuedAt: '2026-10-13T13:00:00Z',
  terminalId: 'demo-manager',
  subtotal: 11.4,
  taxTotal: 0.94,
  total: 12.34,
  fiscalHash: 'abc',
  lines: [
    {
      lineNo: 1,
      sku: 'BURGER-CLASSIC',
      name: 'Classic burger',
      quantity: 1,
      net: 11.4,
      tax: 0.94,
      total: 12.34,
    },
  ],
};

describe('OrderDetail refund', () => {
  it('validates the selection, then posts an idempotent refund and shows the server result', async () => {
    const after: Order = {
      ...paid,
      status: 'PARTIALLY_REFUNDED',
      amountRefunded: 12.34,
      lines: paid.lines.map((l) => ({ ...l, refundedQuantity: 1 })),
      refunds: [refund],
    };
    const server = fakeServer([
      ['GET', '/api/v1/orders/order-1', () => json(200, paid)],
      ['POST', '/api/v1/orders/order-1/refunds', () => json(201, { refund, order: after })],
    ]);
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<OrderDetail id="order-1" />, me('MANAGER'));

    await user.click(await screen.findByRole('button', { name: 'Refund' }));
    const form = screen.getByRole('form', { name: 'Refund' });

    // Nothing selected, no reason → client-side validation, no request.
    await user.click(within(form).getByRole('button', { name: 'Issue refund' }));
    expect(within(form).getByText('Select at least one item to refund.')).toBeInTheDocument();
    expect(within(form).getByText('A reason is required.')).toBeInTheDocument();
    expect(server.calls('POST', /refunds$/)).toHaveLength(0);

    const qty = within(form).getByLabelText('#1 Classic burger');
    await user.clear(qty);
    await user.type(qty, '1');
    await user.click(within(form).getByLabelText('Put items back in stock'));
    await user.type(within(form).getByLabelText('Reason'), 'Cold food');
    await user.click(within(form).getByRole('button', { name: 'Issue refund' }));

    expect(
      await screen.findByText('Refund REF-HOSP01-20261013-0001 issued: $12.34.'),
    ).toBeInTheDocument();
    const [req] = server.calls('POST', '/api/v1/orders/order-1/refunds');
    expect(req?.body).toEqual({
      lines: [{ lineNo: 1, quantity: 1 }],
      restock: false,
      method: 'CASH',
      reason: 'Cold food',
    });
    expect(req?.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(localStorage.getItem('edge.pending.backoffice')).toBeNull();
    expect(screen.getByText('Partially refunded')).toBeInTheDocument();
    expect(screen.getByText('REF-HOSP01-20261013-0001')).toBeInTheDocument();
  });

  it('clamps refund quantities to what is left', async () => {
    vi.stubGlobal(
      'fetch',
      fakeServer([['GET', '/api/v1/orders/order-1', () => json(200, paid)]]).fetch,
    );
    const user = userEvent.setup();
    renderAs(<OrderDetail id="order-1" />, me('MANAGER'));
    await user.click(await screen.findByRole('button', { name: 'Refund' }));
    const qty = screen.getByLabelText('#1 Classic burger');
    await user.clear(qty);
    await user.type(qty, '9');
    expect(qty).toHaveValue(2);
  });

  it('reprints the receipt', async () => {
    vi.stubGlobal(
      'fetch',
      fakeServer([['GET', '/api/v1/orders/order-1', () => json(200, paid)]]).fetch,
    );
    const user = userEvent.setup();
    renderAs(<OrderDetail id="order-1" />, me('MANAGER'));
    await user.click(await screen.findByRole('button', { name: 'Reprint receipt' }));
    const receipt = screen.getByTestId('receipt');
    expect(receipt).toHaveTextContent('COPY');
    expect(within(receipt).getByTestId('receipt-change')).toHaveTextContent('$25.32');
  });

  it('voids a payment of a PLACED order with an idempotent request', async () => {
    const partly = placedOrder({
      amountPaid: 10,
      amountDue: 14.68,
      payments: [cashPayment({ id: 'pay-1', amount: 10, tendered: 10, change: 0 })],
    });
    const voided = placedOrder({
      payments: [
        cashPayment({
          id: 'pay-1',
          amount: 10,
          tendered: 10,
          change: 0,
          voidedAt: '2026-10-13T12:05:00Z',
          voidReason: 'Customer changed mind',
        }),
      ],
    });
    const server = fakeServer([
      ['GET', '/api/v1/orders/order-1', () => json(200, partly)],
      ['POST', '/api/v1/orders/order-1/payments/pay-1/void', () => json(200, voided)],
    ]);
    vi.stubGlobal('fetch', server.fetch);
    const user = userEvent.setup();
    renderAs(<OrderDetail id="order-1" />, me('MANAGER'));
    await user.click(await screen.findByRole('button', { name: 'Void payment $10.00' }));
    await user.type(screen.getByLabelText(/Reason for voiding/), 'Customer changed mind');
    await user.click(screen.getByRole('button', { name: 'Void this payment' }));
    expect(await screen.findByText('Voided: Customer changed mind')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel order' })).toBeEnabled();
    const [req] = server.calls('POST', '/api/v1/orders/order-1/payments/pay-1/void');
    expect(req?.headers['Idempotency-Key']).toBeTruthy();
  });
});

import { expect, test } from '@playwright/test';
import { api, KEYS, signIn, SKU } from './support';

test('manager refunds one line of a paid order', async ({ page, request }) => {
  await api.restock(request, SKU.burger);
  const order = await api.placeOrder(request, [{ sku: SKU.burger, quantity: 2 }]);
  await api.payCash(request, order);

  await signIn(page, KEYS.manager, `/#/backoffice/orders/${order.id}`);
  await expect(page.getByText(order.orderNumber).first()).toBeVisible();
  await expect(page.getByText('Paid', { exact: true }).first()).toBeVisible();

  await page.getByRole('button', { name: 'Refund', exact: true }).click();
  const form = page.getByRole('form', { name: 'Refund' });
  await form.getByRole('spinbutton').first().fill('1');
  await form.getByLabel('Reason').fill('Cold food (e2e)');

  const refunded = page.waitForResponse((r) => /\/refunds$/.test(r.url()));
  await form.getByRole('button', { name: 'Issue refund' }).click();
  const res = await refunded;
  expect(res.status()).toBe(201);
  expect(res.request().headers()['idempotency-key']).toBeTruthy();

  await expect(page.getByText(/Refund REF-.* issued/)).toBeVisible();
  await expect(page.getByText('Partially refunded').first()).toBeVisible();
});

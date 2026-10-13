import { expect, test } from '@playwright/test';
import { api, KEYS, signIn, SKU } from './support';

test('a new order appears on the kitchen display and can be bumped to served', async ({
  page,
  request,
}) => {
  await api.restock(request, SKU.burger);
  await signIn(page, KEYS.kitchen);
  await expect(page.getByRole('heading', { name: 'Kitchen display' })).toBeAttached();
  await expect(page.getByText('Live')).toBeVisible();

  // Placed after the KDS is open: arrives over the live stream (outbox → kitchen).
  const order = await api.placeOrder(request, [{ sku: SKU.burger, quantity: 1 }]);
  const card = page.getByTestId(`ticket-${order.orderNumber}`);
  await expect(
    page.getByRole('list', { name: 'Queued' }).getByTestId(`ticket-${order.orderNumber}`),
  ).toBeVisible({
    timeout: 15_000,
  });

  await card.getByRole('button', { name: `Start ${order.orderNumber}` }).click();
  await expect(
    page.getByRole('list', { name: 'In progress' }).getByTestId(`ticket-${order.orderNumber}`),
  ).toBeVisible();

  await card.getByRole('button', { name: `Ready ${order.orderNumber}` }).click();
  await expect(
    page.getByRole('list', { name: 'Ready' }).getByTestId(`ticket-${order.orderNumber}`),
  ).toBeVisible();

  await card.getByRole('button', { name: `Served ${order.orderNumber}` }).click();
  await expect(card).toHaveCount(0);
});

import { expect, test } from '@playwright/test';
import { api, KEYS, signIn, SKU } from './support';

test.beforeEach(async ({ request }) => {
  await api.restock(request, SKU.burger);
  await api.restock(request, SKU.latte);
});

test('checkout, cash payment with change, receipt', async ({ page }) => {
  await signIn(page, KEYS.pos);

  await page.locator(`[data-sku="${SKU.burger}"]`).click();
  await page.locator(`[data-sku="${SKU.latte}"]`).click();
  await expect(page.getByText('Total calculated at checkout')).toBeVisible();

  const placed = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/orders') && r.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Checkout' }).click();
  const order = (await (await placed).json()) as { orderNumber: string; amountDue: number };
  expect((await placed).status()).toBe(201);

  // Payment screen shows the server's amount due.
  await expect(page.getByTestId('amount-due')).toBeVisible();
  await expect(page.getByRole('heading', { name: `Order ${order.orderNumber}` })).toBeVisible();

  // Tender the largest quick-cash note so there is change.
  await page.getByRole('group', { name: 'Quick cash amounts' }).getByRole('button').last().click();
  const paid = page.waitForResponse((r) => /\/payments$/.test(r.url()));
  await page.getByRole('button', { name: 'Take cash payment' }).click();
  const payment = (await (await paid).json()) as {
    payment: { change: number };
    order: { status: string };
  };
  expect(payment.order.status).toBe('PAID');

  const receipt = page.getByTestId('receipt');
  await expect(receipt).toBeVisible();
  await expect(receipt).toContainText(order.orderNumber);
  await expect(receipt.getByTestId('receipt-change')).toBeVisible();
  await expect(page.getByText(/Change due:/)).toBeVisible();

  await page.getByRole('button', { name: 'New sale' }).click();
  await expect(page.getByRole('button', { name: 'Checkout' })).toBeDisabled(); // empty cart
});

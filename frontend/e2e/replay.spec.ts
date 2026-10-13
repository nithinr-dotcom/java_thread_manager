import { expect, test } from '@playwright/test';
import { api, KEYS, signIn, SKU } from './support';

test.beforeEach(async ({ request }) => {
  await api.restock(request, SKU.burger);
});

/**
 * The response to the checkout is lost after the server committed it (we let the request through
 * and then abort it). The page is reloaded; the unfinished checkout is offered again and the retry
 * gets the SAME order back (Idempotent-Replayed: true) — never a second order.
 */
test('unfinished checkout survives a reload and is replayed with the same key', async ({
  page,
}) => {
  await signIn(page, KEYS.pos);

  let committedNumber: string | null = null;
  let sentKey: string | null = null;
  let dropResponses = true;
  await page.route('**/api/v1/orders', async (route) => {
    if (route.request().method() !== 'POST' || !dropResponses) return route.continue();
    if (committedNumber === null) {
      sentKey = route.request().headers()['idempotency-key'] ?? null;
      const res = await route.fetch(); // reaches the server: the order IS committed …
      committedNumber = ((await res.json()) as { orderNumber: string }).orderNumber;
    }
    return route.abort('connectionreset'); // … but the terminal never hears about it
  });

  await page.locator(`[data-sku="${SKU.burger}"]`).click();
  await page.getByRole('button', { name: 'Checkout' }).click();
  await expect(page.getByTestId('pending-banner')).toContainText('outcome unknown');
  expect(committedNumber).not.toBeNull();

  await page.reload();
  const banner = page.getByTestId('pending-banner');
  await expect(banner).toContainText('Unfinished checkout');

  dropResponses = false;
  const replay = page.waitForResponse(
    (r) => r.url().endsWith('/api/v1/orders') && r.request().method() === 'POST',
  );
  await banner.getByRole('button', { name: 'Retry' }).click();
  const res = await replay;
  expect(res.request().headers()['idempotency-key']).toBe(sentKey);
  expect(res.status()).toBe(200);
  expect(res.headers()['idempotent-replayed']).toBe('true');
  expect(((await res.json()) as { orderNumber: string }).orderNumber).toBe(committedNumber);

  await expect(page.getByRole('heading', { name: `Order ${committedNumber}` })).toBeVisible();
  await expect(page.getByTestId('pending-banner')).toHaveCount(0);
});

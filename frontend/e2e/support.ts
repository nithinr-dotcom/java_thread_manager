import { expect, type APIRequestContext, type Page } from '@playwright/test';

/** API keys seeded by the backend demo profile. */
export const KEYS = {
  manager: 'sf_demo_manager',
  pos: 'sf_demo_pos',
  kitchen: 'sf_demo_kitchen',
  hq: 'sf_demo_hq',
} as const;

export const SKU = {
  mealbox: 'MEALBOX-CHICKEN',
  water: 'WATER-SPARKLING',
  burger: 'BURGER-CLASSIC',
  latte: 'LATTE-LARGE',
} as const;

interface OrderJson {
  id: string;
  orderNumber: string;
  status: string;
  amountDue: number;
  currency: string;
}

const auth = (key: string) => ({ Authorization: `Bearer ${key}` });

/** Pair the browser with a device key through the sign-in screen. */
export async function signIn(page: Page, key: string, path = '/'): Promise<void> {
  await page.goto(path);
  await page.getByLabel('Device API key').fill(key);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
}

/** Direct API helpers (manager key) to arrange test data. */
export const api = {
  async restock(request: APIRequestContext, sku: string, quantity = 20): Promise<void> {
    const r = await request.post(`/api/v1/inventory/${sku}/restock`, {
      headers: { ...auth(KEYS.manager), 'Idempotency-Key': crypto.randomUUID() },
      data: { quantity, note: 'e2e' },
    });
    expect(r.status()).toBe(200);
  },

  async placeOrder(
    request: APIRequestContext,
    items: { sku: string; quantity: number }[],
  ): Promise<OrderJson> {
    const r = await request.post('/api/v1/orders', {
      headers: { ...auth(KEYS.manager), 'Idempotency-Key': crypto.randomUUID() },
      data: { items },
    });
    expect(r.status()).toBe(201);
    return (await r.json()) as OrderJson;
  },

  async payCash(request: APIRequestContext, order: OrderJson): Promise<void> {
    const r = await request.post(`/api/v1/orders/${order.id}/payments`, {
      headers: { ...auth(KEYS.manager), 'Idempotency-Key': crypto.randomUUID() },
      data: { method: 'CASH', amount: order.amountDue, tendered: order.amountDue },
    });
    expect(r.status()).toBe(201);
  },
};

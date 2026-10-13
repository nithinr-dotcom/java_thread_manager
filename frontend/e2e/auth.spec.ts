import { expect, test } from '@playwright/test';
import { KEYS, signIn } from './support';

test.describe('device pairing', () => {
  test('manager key unlocks every app; sign-out forgets the key', async ({ page }) => {
    await signIn(page, KEYS.manager);
    const nav = page.getByRole('navigation', { name: 'Apps' });
    for (const app of ['POS', 'Kitchen', 'Back office', 'Developer tools']) {
      await expect(nav.getByRole('link', { name: app })).toBeVisible();
    }
    await nav.getByRole('link', { name: 'Back office' }).click();
    await expect(page.getByRole('heading', { name: 'Orders', level: 1 })).toBeVisible();

    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.getByRole('heading', { name: 'Pair this device' })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Pair this device' })).toBeVisible();
  });

  test('POS key only gets the POS', async ({ page }) => {
    await signIn(page, KEYS.pos, '/#/backoffice/devices');
    await expect(page.getByRole('navigation', { name: 'Apps' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Checkout' })).toBeVisible();
    await expect(page).toHaveURL(/#\/pos$/);
  });

  test('HQ key is not a UI role', async ({ page }) => {
    await signIn(page, KEYS.hq);
    await expect(page.getByRole('heading', { name: 'Not a UI role' })).toBeVisible();
  });

  test('an unknown key is rejected', async ({ page }) => {
    await page.goto('/');
    await page.getByLabel('Device API key').fill('sf_not_a_real_key');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByText('This key was not accepted')).toBeVisible();
  });
});

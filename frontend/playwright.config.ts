import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests against a REAL backend (demo profile: seeded API keys + demo catalog).
 *   ./mvnw spring-boot:test-run          # backend on :8080
 *   npm run e2e                          # starts `npm run dev` on :5173 unless already running
 * Point at another deployment with E2E_BASE_URL (then no dev server is started).
 */
const external = process.env.E2E_BASE_URL;
const baseURL = external ?? 'http://localhost:5173';

export default defineConfig({
  testDir: './e2e',
  // Tests share one backend (stock, fiscal numbers): run them one at a time.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // Optional: reuse an already installed Chromium (e.g. offline machines) instead of `playwright install`.
        ...(process.env.PLAYWRIGHT_CHROMIUM_PATH
          ? { launchOptions: { executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH } }
          : {}),
      },
    },
  ],
  ...(external
    ? {}
    : {
        webServer: {
          command: 'npm run dev',
          url: baseURL,
          reuseExistingServer: true,
          timeout: 60_000,
        },
      }),
});

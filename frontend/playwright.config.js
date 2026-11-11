import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright config for the e2e smoke suite.
 *
 * Self-contained: it does NOT boot the backend or the frontend dev server and
 * does NOT depend on any Vite/Vitest config. CI (`.github/workflows/e2e.yml`)
 * starts the packaged backend with the built SPA and sets `E2E_BASE_URL`;
 * locally, start the stack first (see `e2e/README.md`). Vitest's glob does not
 * reach into `e2e/`.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? 'github' : 'list',

  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 5_000,
    navigationTimeout: 15_000
  },

  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],

  outputDir: 'test-results/'
})

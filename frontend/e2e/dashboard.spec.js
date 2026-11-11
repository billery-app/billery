import { test, expect } from '@playwright/test'

/**
 * Read-only smoke test: the dashboard route renders, the SPA shell is
 * served by whatever is running at `baseURL` (Vite dev on :5173 by
 * default), and the period selector exposes the six period buttons.
 *
 * The assertions are deliberately minimal — they only check what the
 * test name claims, so the test stays green against both a PostgreSQL dev
 * DB with dummies and a self-contained H2 (desktop profile) DB with
 * no rows.
 *
 * Why role-based locators and not text: the stat-card labels and the
 * "Statistiken" section text both contain substrings like "Gesamt
 * Rechnungen" / "Gesamt Rechnungen:" and would trigger Playwright's
 * strict-mode. Period buttons have unique accessible names, so a
 * `getByRole('button', { name })` locator resolves to one element.
 */
test('dashboard route loads and shows the period selector', async ({ page }) => {
  const response = await page.goto('/')
  expect(response, 'GET / returned no response').not.toBeNull()
  expect(response.status(), 'GET / did not return 2xx').toBeLessThan(400)

  await expect(page.getByRole('heading', { name: 'Dashboard', level: 1 })).toBeVisible()

  for (const label of [
    'Aktueller Monat',
    'Letzter Monat',
    'Aktuelles Jahr',
    'Letztes Jahr',
    'Letzte 12 Monate',
    'Benutzerdefiniert'
  ]) {
    await expect(page.getByRole('button', { name: label })).toBeVisible()
  }
})

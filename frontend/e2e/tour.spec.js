import { test, expect } from '@playwright/test'

/**
 * The introductory tour (#14), started by hand under Einstellungen → Hilfe: all five stations,
 * each on its page, then "Fertig"; and "Überspringen" ends it at once. Fails on console errors,
 * uncaught exceptions and /api responses >= 400 like the other specs. Changes no data: the
 * end of the tour stores `seen`, which `global-setup.js` has already set.
 */

function watch(page) {
  const problems = []
  page.on('console', message => {
    if (message.type() === 'error') problems.push(`console: ${message.text()}`)
  })
  page.on('pageerror', error => problems.push(`pageerror: ${error.message}`))
  page.on('response', response => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/') && response.status() >= 400) {
      problems.push(`${response.status()} ${response.request().method()} ${url.pathname}`)
    }
  })
  return problems
}

const STATIONS = [
  { title: 'Willkommen bei Billery', path: '/settings/help' },
  { title: 'Stammdaten und Katalog', path: '/articles-services', query: 'tab=articles' },
  { title: 'Eine Rechnung zusammenklicken', path: '/invoices', query: 'type=invoice' },
  { title: 'Vom Angebot zur Rechnung', path: '/invoices', query: 'type=offer' },
  { title: 'Ausstattung und Amortisation', path: '/articles-services', query: 'tab=equipment' }
]

async function startFromHelp(page) {
  await page.goto('/settings/help')
  await page.getByTestId('help-start-tour').click()
}

test('the tour leads through its five stations and ends with "Fertig"', async ({ page }) => {
  const problems = watch(page)
  await startFromHelp(page)
  const popover = page.locator('.driver-popover')

  for (const [index, station] of STATIONS.entries()) {
    await expect(popover.locator('.driver-popover-title')).toHaveText(station.title)
    await expect(popover.locator('.driver-popover-progress-text')).toHaveText(`${index + 1} von 5`)
    const url = new URL(page.url())
    expect(url.pathname).toBe(station.path)
    if (station.query) expect(url.search).toBe(`?${station.query}`)
    await popover.getByRole('button', { name: index === STATIONS.length - 1 ? 'Fertig' : 'Weiter' }).click()
  }
  await expect(popover).toHaveCount(0)
  // the last station opened the equipment section of the catalog
  await expect(page.getByRole('button', { name: 'Ausstattung', exact: true })).toHaveAttribute('aria-pressed', 'true')
  expect(problems).toEqual([])
})

test('"Überspringen" ends the tour at once', async ({ page }) => {
  const problems = watch(page)
  await startFromHelp(page)
  const popover = page.locator('.driver-popover')
  await expect(popover.locator('.driver-popover-title')).toHaveText(STATIONS[0].title)
  await popover.getByRole('button', { name: 'Überspringen' }).click()
  await expect(popover).toHaveCount(0)
  expect(problems).toEqual([])
})

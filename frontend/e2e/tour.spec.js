import { test, expect } from '@playwright/test'

/**
 * The introductory tour (#14), started by hand under Einstellungen → Hilfe: all five stations,
 * each on its page, then "Fertig"; and "Überspringen" ends it at once. Fails on console errors,
 * uncaught exceptions and /api responses >= 400 like the other specs. The
 * end of the tour stores `seen`, which `global-setup.js` has already set. Company data are created
 * for the editor if none exist, and deleted again.
 */

// One worker for this file: beforeAll/afterAll share the company data.
test.describe.configure({ mode: 'serial' })

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

/** Set only when this run created the company data; afterAll deletes them again. */
let createdOwnerId

// The editor reads the company data (an empty database answers 404, which the specs count as a problem).
test.beforeAll(async ({ request }) => {
  const owner = await request.get('/api/owners/primary')
  if (owner.ok() && (await owner.text()).trim()) return
  const created = await request.post('/api/owners', {
    headers: { Origin: new URL(test.info().project.use.baseURL).origin },
    data: {
      firstName: 'E2E', lastName: 'Tour', companyName: 'E2E Tour GmbH', companyType: 'GmbH',
      postalCode: '12345', city: 'Teststadt', street: 'Testweg', houseNumber: 1, phone: '0',
      email: 'e2e@example.invalid', url: '', taxId: 'DE000000000', iban: 'DE02120300000000202051',
      isSmallBusiness: false
    }
  })
  expect(created.ok(), 'creating the company data failed').toBeTruthy()
  createdOwnerId = (await created.json()).id
})

test.afterAll(async ({ request }) => {
  if (!createdOwnerId) return
  const deleted = await request.delete(`/api/owners/${createdOwnerId}`, {
    headers: { Origin: new URL(test.info().project.use.baseURL).origin }
  })
  expect(deleted.ok(), 'deleting the test company data failed').toBeTruthy()
})

test('the templates tour opens the editor for its editor stations and closes it going back', async ({ page }) => {
  const problems = watch(page)
  await page.goto('/templates')
  await page.getByTestId('templates-tour').click()
  const popover = page.locator('.driver-popover')
  const titles = [
    'Ihre Dokumentvorlagen',
    'Eine Vorlage pro Dokumenttyp',
    'Design und Blöcke',
    'Vorschau und Platzhalter',
    'PDF-Vorschau und Speichern'
  ]
  for (const [index, title] of titles.entries()) {
    await expect(popover.locator('.driver-popover-title')).toHaveText(title)
    await expect(page.locator('.template-editor')).toHaveCount(index >= 2 ? 1 : 0)
    if (index < titles.length - 1) await popover.getByRole('button', { name: 'Weiter' }).click()
  }
  // back from the editor to the list closes the editor (nothing was changed, no question)
  await popover.getByRole('button', { name: 'Zurück' }).click()
  await popover.getByRole('button', { name: 'Zurück' }).click()
  await popover.getByRole('button', { name: 'Zurück' }).click()
  await expect(popover.locator('.driver-popover-title')).toHaveText(titles[1])
  await expect(page.locator('.template-editor')).toHaveCount(0)
  await popover.getByRole('button', { name: 'Überspringen' }).click()
  await expect(popover).toHaveCount(0)
  expect(problems).toEqual([])
})

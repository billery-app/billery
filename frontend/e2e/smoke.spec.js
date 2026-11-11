import { test, expect } from '@playwright/test'

/**
 * Smoke test of the built product: SPA + API served by the backend, used in a real browser.
 * Every test fails on a console error, an uncaught exception, a Content-Security-Policy violation
 * or an /api response >= 400 — the class of breakage unit tests cannot see (a CSP once kept
 * the template editor's PDF preview blank for days while every check was green).
 *
 * Runs in CI (`.github/workflows/e2e.yml`) against an empty H2 database; locally against any
 * running instance via E2E_BASE_URL. It creates its own customer and draft (and the company data,
 * if none exist) and deletes all of them again.
 */

// One worker for this file: beforeAll/afterAll share one customer and one draft.
test.describe.configure({ mode: 'serial' })

const MARKER = `E2E-${Date.now()}`
let customerId
let draftId
let equipmentId
/** Set only when this run created the company data; then afterAll deletes them again. */
let createdOwnerId

/** Write requests need the app's own Origin (CSRF protection). */
function originHeader() {
  return { Origin: new URL(test.info().project.use.baseURL).origin }
}

/** Collects everything that must never happen while using the app. */
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

test.beforeAll(async ({ request }) => {
  const headers = originHeader()
  const owner = await request.get('/api/owners/primary')
  if (!owner.ok() || !(await owner.text()).trim()) {
    const created = await request.post('/api/owners', {
      headers,
      data: {
        firstName: 'E2E', lastName: 'Smoke', companyName: 'E2E Smoke GmbH', companyType: 'GmbH',
        postalCode: '12345', city: 'Teststadt', street: 'Testweg', houseNumber: 1, phone: '0',
        email: 'e2e@example.invalid', url: '', taxId: 'DE000000000', iban: 'DE02120300000000202051',
        isSmallBusiness: false
      }
    })
    expect(created.ok(), 'creating the company data failed').toBeTruthy()
    createdOwnerId = (await created.json()).id
  }
  const customer = await request.post('/api/customers', {
    headers,
    data: {
      firstName: MARKER, lastName: 'Kunde', postalCode: '12345', city: 'Teststadt', street: 'Testweg',
      houseNumber: 1, phone: '', email: '', taxId: '', note: '', buyerReference: ''
    }
  })
  expect(customer.ok(), 'creating the test customer failed').toBeTruthy()
  customerId = (await customer.json()).id
  const today = new Date().toISOString().slice(0, 10)
  const draft = await request.post('/api/invoices', {
    headers,
    data: {
      customerId, invoiceDate: today, dueDate: today, workStartDate: today, taxRate: 19, documentTypeId: 1,
      items: [{ itemType: 'service', itemName: 'E2E Beratung', unit: 'Std', quantity: 1, unitPrice: 100, taxRate: 19 }]
    }
  })
  expect(draft.ok(), 'creating the test draft failed').toBeTruthy()
  draftId = (await draft.json()).id
  const equipment = await request.post('/api/equipment', {
    headers,
    data: { name: `${MARKER} Gerät`, purchaseValue: 1000, acquisitionDate: today, expectedLifetimeMonths: 60, monthlyOperatingCost: 0 }
  })
  expect(equipment.ok(), 'creating the test equipment failed').toBeTruthy()
  equipmentId = (await equipment.json()).id
})

test.afterAll(async ({ request }) => {
  const headers = originHeader()
  if (draftId) await request.delete(`/api/invoices/${draftId}`, { headers })
  if (equipmentId) await request.delete(`/api/equipment/${equipmentId}`, { headers })
  if (customerId) await request.delete(`/api/customers/${customerId}`, { headers })
  // Never leave made-up company data (tax id, IBAN) behind: they would appear on real invoices.
  if (createdOwnerId) {
    const deleted = await request.delete(`/api/owners/${createdOwnerId}`, { headers })
    expect(deleted.ok(), 'deleting the test company data failed').toBeTruthy()
  }
})

test('the main screens load without errors', async ({ page }) => {
  const problems = watch(page)
  for (const [path, heading] of [
    ['/', 'Dashboard'],
    ['/invoices', 'Rechnungsverwaltung'],
    ['/customers', 'Kundenverwaltung'],
    ['/articles-services', 'Artikel & Leistungen'],
    ['/templates', 'Dokumentvorlagen'],
    ['/settings', 'Einstellungen']
  ]) {
    await page.goto(path)
    await expect(page.getByRole('heading', { name: heading, level: 1 }).first(), path).toBeVisible()
    await page.waitForLoadState('networkidle')
  }
  expect(problems).toEqual([])
})

test('the equipment amortisation opens the usage dialog', async ({ page }) => {
  const problems = watch(page)
  await page.goto('/articles-services')
  await page.getByRole('button', { name: 'Ausstattung', exact: true }).click()
  const row = page.locator('tbody tr', { hasText: MARKER })
  await expect(row).toBeVisible()
  await expect(row.locator('.amort-cell')).toContainText('0 %')

  const usage = page.waitForResponse(r => new URL(r.url()).pathname === `/api/equipment/${equipmentId}/usage`)
  await row.locator('.amort-cell').click()
  expect((await usage).status()).toBe(200)
  const dialog = page.getByRole('dialog', { name: `Einsätze: ${MARKER} Gerät` })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('Keine Einsätze gefunden')
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  expect(problems).toEqual([])
})

test('the template editor shows its PDF preview (blob: frame allowed by the CSP)', async ({ page }) => {
  const problems = watch(page)
  await page.goto('/templates')
  await page.getByRole('button', { name: 'Bearbeiten' }).first().click()

  const preview = page.waitForResponse(r => new URL(r.url()).pathname === '/api/templates/preview')
  await page.getByRole('button', { name: 'PDF-Vorschau' }).first().click()
  expect((await preview).status()).toBe(200)

  const frame = page.locator('iframe.pdf-frame')
  await expect(frame).toBeAttached()
  expect(await frame.getAttribute('src')).toMatch(/^blob:/)
  // A blocked frame shows up as a console error ("violates the following Content Security Policy").
  await page.waitForTimeout(1000)
  expect(problems).toEqual([])
})

test('a document opens from the list and the customer is searched in the document form', async ({ page }) => {
  const problems = watch(page)
  await page.goto('/invoices')
  await page.getByPlaceholder('Suchen...').fill(MARKER)
  const row = page.locator('tbody tr', { hasText: MARKER })
  await expect(row).toHaveCount(1)
  await row.click()
  await expect(page.getByRole('dialog').or(page.locator('.modal-backdrop')).first()).toBeVisible()

  // The action bar of the view: the next step is the one primary button, the rare and
  // destructive actions sit behind "Mehr".
  const bar = page.locator('.modal-footer .action-bar')
  await expect(bar.locator('.btn-primary')).toHaveText(['Ausstellen'])
  // The lifecycle strip is captioned and holds the step; "PDF" is not part of it.
  const strip = bar.getByRole('group', { name: 'Nächster Schritt:' })
  await expect(strip.getByRole('button')).toHaveText(['Ausstellen'])
  await bar.getByRole('button', { name: 'Mehr' }).click()
  await expect(page.getByRole('menuitem')).toHaveText(['Löschen'])
  await page.keyboard.press('Escape')
  await expect(page.getByRole('menu')).toHaveCount(0)
  await expect(bar.getByRole('button', { name: 'Mehr' })).toBeFocused()

  await page.keyboard.press('Escape')
  await page.goto('/invoices')

  await page.getByRole('button', { name: 'Neue Rechnung' }).click()
  const picker = page.getByTestId('customer-picker-input')
  await picker.click()
  await picker.fill(MARKER)
  const option = page.getByRole('option', { name: new RegExp(MARKER) })
  await expect(option).toBeVisible()
  // Click the option: Enter would pick the highlighted entry, which is still a stale one while the
  // filtered list loads, as soon as the database holds more than one customer.
  await option.click()
  await expect(picker).toHaveValue(`${MARKER} Kunde`)
  expect(problems).toEqual([])
})

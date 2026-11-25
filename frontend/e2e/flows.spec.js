import { test, expect } from '@playwright/test'

/**
 * The core write flows of the built product in a real browser (#24, threat model G11): a customer
 * and an invoice created through the forms, issued, paid in two parts, sent by mail, cancelled by
 * a credit note and exported for GoBD; an offer created and accepted. Like `smoke.spec.js` every
 * step fails on a console error, an uncaught exception or an /api response >= 400.
 *
 * Issued documents are retained by design (GoBD): run this against the empty CI database or a
 * throwaway instance, never against real data. The mail step needs a Mailpit
 * (`E2E_MAILPIT_URL`, SMTP on `E2E_SMTP_PORT`, see e2e/README.md) and is skipped without one.
 */

test.describe.configure({ mode: 'serial' })

const STAMP = Date.now()
const MARKER = `Ablauf${STAMP}`
const YEAR = new Date().getFullYear()
const TODAY = new Date().toISOString().slice(0, 10)
const MAILPIT = process.env.E2E_MAILPIT_URL
const SMTP_PORT = Number(process.env.E2E_SMTP_PORT || 1025)

/** Set only when this run created the company data; then afterAll deletes them again. */
let createdOwnerId
let serviceId
let mailAccountId
/** The number the invoice got when it was issued, e.g. RE-2026-001. */
let invoiceNumber

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
  // Every confirmation of the flows is answered with yes, except the offer to copy a cancelled
  // invoice as a new draft (that would leave a draft behind).
  page.on('dialog', dialog =>
    dialog.message().includes('Kopie der Rechnung') ? dialog.dismiss() : dialog.accept()
  )
  return problems
}

test.beforeAll(async ({ request }) => {
  const headers = originHeader()
  const owner = await request.get('/api/owners/primary')
  if (!owner.ok() || !(await owner.text()).trim()) {
    const created = await request.post('/api/owners', {
      headers,
      data: {
        firstName: 'E2E', lastName: 'Ablauf', companyName: 'E2E Ablauf GmbH', companyType: 'GmbH',
        postalCode: '12345', city: 'Teststadt', street: 'Testweg', houseNumber: 1, phone: '0',
        email: 'e2e@example.invalid', url: '', taxId: 'DE000000000', iban: 'DE02120300000000202051',
        isSmallBusiness: false
      }
    })
    expect(created.ok(), 'creating the company data failed').toBeTruthy()
    createdOwnerId = (await created.json()).id
  }
  // The catalog entry the forms pick; the forms themselves are what is tested.
  const service = await request.post('/api/services', {
    headers,
    data: { name: `${MARKER} Beratung`, description: 'E2E', hourlyRate: 100, unit: 'Std', taxRate: 19 }
  })
  expect(service.ok(), 'creating the catalog service failed').toBeTruthy()
  serviceId = (await service.json()).id
})

test.afterAll(async ({ request }) => {
  const headers = originHeader()
  if (mailAccountId) await request.delete(`/api/mail/accounts/${mailAccountId}`, { headers })
  if (serviceId) await request.delete(`/api/services/${serviceId}`, { headers })
  // Never leave made-up company data (tax id, IBAN) behind: they would appear on real invoices.
  if (createdOwnerId) {
    const deleted = await request.delete(`/api/owners/${createdOwnerId}`, { headers })
    expect(deleted.ok(), 'deleting the test company data failed').toBeTruthy()
  }
})

/** Fills the document form: the test customer, the service date and one line of the service. */
async function fillDocumentForm(page) {
  const form = page.locator('.document-modal')
  const picker = form.getByTestId('customer-picker-input')
  await picker.click()
  await picker.fill(MARKER)
  await page.getByRole('option', { name: new RegExp(MARKER) }).click()
  await form.locator('.form-group', { hasText: 'Leistungsdatum' }).locator('input[type="date"]').fill(TODAY)
  await form.getByRole('button', { name: 'Leistungen', exact: true }).click()
  const select = form.locator('.item-selector select')
  await select.selectOption(await select.locator('option', { hasText: MARKER }).getAttribute('value'))
  await form.getByRole('button', { name: 'Hinzufügen' }).click()
  await expect(form.locator('.items-tree')).toHaveCount(1)
  await form.getByRole('button', { name: 'Speichern', exact: true }).click()
  await expect(form).toHaveCount(0)
}

/** Opens the document of the test customer from the list of the current tab. */
async function openFromList(page) {
  await page.getByPlaceholder('Suchen...').fill(MARKER)
  const row = page.locator('tbody tr', { hasText: MARKER })
  await expect(row).toHaveCount(1)
  await row.click()
  await expect(page.locator('.modal-footer .action-bar')).toBeVisible()
}

const statusBadge = page => page.locator('.modal-panel .status-badge').first()

test('a customer and an invoice go through the whole lifecycle in the browser', async ({ page }) => {
  const problems = watch(page)

  // Customer through the customer form
  await page.goto('/customers')
  await page.getByRole('button', { name: 'Neuer Kunde' }).click()
  await page.locator('#customer-firstName').fill(MARKER)
  await page.locator('#customer-lastName').fill('Kundin')
  await page.locator('#customer-street').fill('Teststraße')
  await page.locator('#customer-houseNumber').fill('5')
  await page.locator('#customer-postalCode').fill('12345')
  await page.locator('#customer-city').fill('Teststadt')
  await page.locator('#customer-email').fill(`kundin-${STAMP}@example.invalid`)
  await page.getByRole('button', { name: 'Speichern', exact: true }).click()
  await expect(page.locator('#customer-firstName')).toHaveCount(0)

  // Invoice through the document form, saved as a draft
  await page.goto('/invoices')
  await page.getByRole('button', { name: 'Neue Rechnung' }).click()
  await fillDocumentForm(page)
  await openFromList(page)
  await expect(statusBadge(page)).toHaveText('Entwurf')

  // Issue: the number is assigned now
  const bar = page.locator('.modal-footer .action-bar')
  await bar.getByRole('button', { name: 'Ausstellen' }).click()
  await expect(statusBadge(page)).toHaveText('Gesendet')
  const title = await page.locator('.modal-header h2').textContent()
  invoiceNumber = title.match(/RE-\d{4}-\d{3,}/)[0]
  expect(invoiceNumber).toContain(`RE-${YEAR}-`)

  // A partial payment, then the rest (the form proposes the open amount)
  await bar.getByRole('button', { name: 'Zahlung erfassen' }).click()
  await page.locator('#payment-amount').fill('50')
  await page.locator('.payment-form').getByRole('button', { name: 'Speichern' }).click()
  await expect(statusBadge(page)).toHaveText('Teilweise bezahlt')
  await bar.getByRole('button', { name: 'Zahlung erfassen' }).click()
  await expect(page.locator('#payment-amount')).toHaveValue('69')
  await page.locator('.payment-form').getByRole('button', { name: 'Speichern' }).click()
  await expect(statusBadge(page)).toHaveText('Bezahlt')

  // Send by mail (needs Mailpit)
  if (MAILPIT) {
    const account = await page.request.post('/api/mail/accounts', {
      headers: originHeader(),
      data: { name: `${MARKER} Konto`, host: '127.0.0.1', port: SMTP_PORT, security: 'NONE', fromAddress: 'rechnung@example.invalid' }
    })
    expect(account.ok(), 'creating the mail account failed').toBeTruthy()
    mailAccountId = (await account.json()).id
    await bar.getByRole('button', { name: 'Per E-Mail' }).click()
    const dialog = page.getByTestId('send-mail')
    await expect(dialog.locator('#send-subject')).toContainText(invoiceNumber)
    await dialog.locator('#send-account').selectOption({ label: `${MARKER} Konto – rechnung@example.invalid` })
    await dialog.getByTestId('send-button').click()
    await expect(page.getByTestId('mail-notice')).toHaveText(`An kundin-${STAMP}@example.invalid gesendet.`)
    const search = await page.request.get(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(invoiceNumber)}`)
    const found = await search.json()
    expect(found.messages.length, 'the mail reached Mailpit').toBe(1)
    expect(found.messages[0].Attachments, 'with the PDF attached').toBe(1)
  }

  // Cancel: a credit note with its own number
  await bar.getByRole('button', { name: 'Mehr' }).click()
  await page.getByRole('menuitem', { name: 'Stornieren' }).click()
  await expect(statusBadge(page)).toHaveText('Storniert')
  await expect(page.locator('.modal-panel')).toContainText(`ST-${YEAR}-`)
  await page.keyboard.press('Escape')

  // GoBD export of this year: a complete ZIP (end of central directory present)
  await page.goto('/settings/export')
  await page.getByTestId('export-from').fill(`${YEAR}-01-01`)
  await page.getByTestId('export-to').fill(`${YEAR}-12-31`)
  const downloading = page.waitForEvent('download')
  await page.getByTestId('export-button').click()
  const download = await downloading
  const zip = Buffer.concat(await streamChunks(await download.createReadStream()))
  const eocd = zip.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]))
  expect(eocd, 'the ZIP has its end of central directory').toBeGreaterThan(zip.length - 200)
  expect(zip.includes(Buffer.from('index.xml')), 'with the GDPdU description').toBe(true)
  expect(zip.includes(Buffer.from(invoiceNumber)), 'with the invoice').toBe(true)

  expect(problems).toEqual([])
})

test('an offer is created in the form and accepted', async ({ page }) => {
  const problems = watch(page)
  await page.goto('/invoices')
  await page.getByRole('button', { name: 'Angebote' }).first().click()
  await page.getByRole('button', { name: 'Neues Angebot' }).click()
  await fillDocumentForm(page)
  await openFromList(page)
  await expect(statusBadge(page)).toHaveText('Entwurf')

  const strip = page.locator('.modal-footer .action-bar').getByRole('group', { name: 'Status setzen auf:' })
  await strip.getByRole('button', { name: 'Angenommen' }).click()
  await expect(statusBadge(page)).toHaveText('Angenommen')
  expect(problems).toEqual([])
})

async function streamChunks(stream) {
  const chunks = []
  for await (const chunk of stream) chunks.push(chunk)
  return chunks
}

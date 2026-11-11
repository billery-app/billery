import { test, expect } from '@playwright/test'

/**
 * Read-only smoke: the /invoices route renders without a 5xx and the
 * list view is interactive.
 *
 * The page may show either the table (DB has rows) or the "Keine
 * Rechnungen vorhanden" empty-state — both prove the route loads.
 */
test('invoices list route loads', async ({ page }) => {
  const response = await page.goto('/invoices')
  expect(response, 'GET /invoices returned no response').not.toBeNull()
  expect(response.status(), 'GET /invoices did not return 2xx').toBeLessThan(400)

  await expect(page.getByRole('heading', { name: 'Rechnungsverwaltung', level: 1 })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Neue Rechnung' })).toBeVisible()

  const searchInput = page.getByPlaceholder('Suchen...')
  await expect(searchInput).toBeVisible()
})

/**
 * Round-trip smoke: POST a draft invoice, verify the count goes up by
 * one, then DELETE it and verify the count returns. Cleans up in a
 * `finally` so a failed assertion still removes the row.
 *
 * Requires at least one customer in the dev DB (PostgreSQL with dummies,
 * or H2 with a seeded customer). If the DB is empty the test skips
 * with a clear reason — running this against a fresh dev DB would
 * create a meaningless "smoke" row.
 *
 * The "verify in UI" step that lived here previously was removed:
 * the `notes` field is not rendered in the list table, so a marker
 * placed there never appears on the page. Counting via the API is
 * the real contract — UI rendering is covered by the read-only test
 * above.
 */
test('create invoice via API, then clean up', async ({ request }) => {
  // Write requests need the app's own Origin (CSRF protection).
  const headers = { Origin: new URL(test.info().project.use.baseURL).origin }
  // An empty database (CI) gets a temporary customer, deleted again at the end.
  const customersResponse = await request.get('/api/customers/page?size=1')
  expect(customersResponse.ok(), 'GET /api/customers/page failed').toBeTruthy()
  let customerId = (await customersResponse.json()).items[0]?.id
  let temporaryCustomerId = null
  if (!customerId) {
    const created = await request.post('/api/customers', {
      headers,
      data: { firstName: 'E2E', lastName: 'Temp', postalCode: '1', city: 'X', street: 'Y', houseNumber: 1, phone: '', email: '', taxId: '', note: '', buyerReference: '' }
    })
    expect(created.ok(), 'creating a temporary customer failed').toBeTruthy()
    customerId = temporaryCustomerId = (await created.json()).id
  }
  const today = new Date().toISOString().slice(0, 10)
  const due = new Date(Date.now() + 14 * 86_400_000).toISOString().slice(0, 10)

  const countInvoices = async () => {
    const response = await request.get('/api/invoices/page?includeArchived=true&size=1')
    expect(response.ok(), 'GET /api/invoices/page failed').toBeTruthy()
    const body = await response.json()
    return body.total
  }

  const before = await countInvoices()

  let createdId
  try {
    const createResponse = await request.post('/api/invoices', {
      headers,
      data: {
        customerId,
        invoiceDate: today,
        dueDate: due,
        documentTypeId: 1,
        documentStatusId: 1,
        taxRate: 19,
        items: [
          {
            itemType: 'SERVICE',
            itemName: 'E2E smoke item',
            quantity: 1,
            unit: 'Stück',
            unitPrice: 0,
            totalPrice: 0,
            taxRate: 19
          }
        ]
      }
    })

    expect(createResponse.ok(), `POST /api/invoices failed: ${createResponse.status()}`).toBeTruthy()
    const created = await createResponse.json()
    createdId = created.id
    expect(createdId, 'created invoice has no id').toBeTruthy()

    expect(await countInvoices(), 'invoice count did not increase by 1').toBe(before + 1)
  } finally {
    if (createdId != null) {
      const deleteResponse = await request.delete(`/api/invoices/${createdId}`, { headers })
      expect(deleteResponse.ok(), `cleanup DELETE /api/invoices/${createdId} failed`).toBeTruthy()
      expect(await countInvoices(), 'invoice count did not return to baseline').toBe(before)
    }
    if (temporaryCustomerId != null) {
      await request.delete(`/api/customers/${temporaryCustomerId}`, { headers })
    }
  }
})

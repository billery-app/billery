import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../services/invoiceService.js', () => ({
  default: {
    createInvoice: vi.fn(),
    updateInvoiceStatus: vi.fn()
  }
}))

import invoiceAPI from '../services/invoiceService.js'
import { convertDocument } from './documentConversion.js'
import { DRAFT, CONVERTED, SENT } from './documentStatus.js'

describe('convertDocument', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    invoiceAPI.createInvoice.mockResolvedValue({ data: { id: 42 } })
    invoiceAPI.updateInvoiceStatus.mockResolvedValue({ data: {} })
  })

  const offer = {
    id: 7,
    customerId: 3,
    notes: 'Danke',
    workStartDate: '2026-10-01',
    workEndDate: null,
    items: [{ itemName: 'Beratung', quantity: 2, unitPrice: 50 }]
  }

  it('creates the invoice as a draft with the source data and the chosen dates', async () => {
    const created = await convertDocument(offer, 1, { invoiceDate: '2026-10-16', dueDate: '2026-10-30' })

    expect(created).toEqual({ id: 42 })
    expect(invoiceAPI.createInvoice).toHaveBeenCalledWith({
      customerId: 3,
      invoiceDate: '2026-10-16',
      dueDate: '2026-10-30',
      workStartDate: '2026-10-01',
      workEndDate: null,
      notes: 'Danke',
      documentStatusId: DRAFT,
      documentTypeId: 1,
      items: offer.items
    })
  })

  it('marks the source converted and links it to the new document', async () => {
    await convertDocument(offer, 1, { invoiceDate: '2026-10-16', dueDate: '' })

    expect(invoiceAPI.updateInvoiceStatus).toHaveBeenCalledWith(7, CONVERTED, { convertedToInvoiceId: 42 })
  })

  it('never issues the new invoice (issuing is an explicit step)', async () => {
    await convertDocument(offer, 1, { invoiceDate: '2026-10-16', dueDate: '' })

    const issued = invoiceAPI.updateInvoiceStatus.mock.calls.filter(([, status]) => status === SENT)
    expect(issued).toEqual([])
  })
})

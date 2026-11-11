import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { useDocumentType } from './useDocumentType.js'
import { DOCUMENT_TYPES } from '../data/documentTypes.js'
import { DRAFT, SENT, ACCEPTED, REJECTED, PAID, CANCELLED, PARTIALLY_PAID } from '../utils/documentStatus.js'

function dayOffset(days) {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  d.setDate(d.getDate() + days)
  return d
}

function isoDate(date) {
  return date.toISOString().slice(0, 10)
}

function makeInvoice(overrides = {}) {
  return {
    id: 1,
    documentStatusId: DRAFT,
    invoiceDate: isoDate(dayOffset(-10)),
    dueDate: isoDate(dayOffset(14)),
    paidDate: null,
    offerValidUntil: null,
    ...overrides,
  }
}

const { applyStatusFilter } = useDocumentType()

describe('useDocumentType — applyStatusFilter', () => {
  it('returns the input array unchanged when filter is the empty string', () => {
    const invoices = [makeInvoice({ id: 1 }), makeInvoice({ id: 2, documentStatusId: PAID })]
    expect(applyStatusFilter(invoices, '')).toBe(invoices)
  })

  it('keeps only paid invoices for the "paid" filter', () => {
    const invoices = [
      makeInvoice({ id: 1, documentStatusId: PAID }),
      makeInvoice({ id: 2, documentStatusId: SENT }),
      makeInvoice({ id: 3, documentStatusId: DRAFT }),
    ]
    const result = applyStatusFilter(invoices, 'paid')
    expect(result.map(inv => inv.id)).toEqual([1])
  })

  it('keeps only draft invoices for the "draft" filter', () => {
    const invoices = [
      makeInvoice({ id: 1, documentStatusId: DRAFT }),
      makeInvoice({ id: 2, documentStatusId: SENT }),
      makeInvoice({ id: 3, documentStatusId: PAID }),
    ]
    const result = applyStatusFilter(invoices, 'draft')
    expect(result.map(inv => inv.id)).toEqual([1])
  })

  it('keeps only sent invoices for the "sent" filter', () => {
    const invoices = [
      makeInvoice({ id: 1, documentStatusId: SENT }),
      makeInvoice({ id: 2, documentStatusId: DRAFT }),
      makeInvoice({ id: 3, documentStatusId: PAID }),
    ]
    const result = applyStatusFilter(invoices, 'sent')
    expect(result.map(inv => inv.id)).toEqual([1])
  })

  it('keeps only overdue invoices for the "overdue" filter', () => {
    const invoices = [
      makeInvoice({ id: 1, documentStatusId: SENT, dueDate: isoDate(dayOffset(-3)) }),
      makeInvoice({ id: 2, documentStatusId: SENT, dueDate: isoDate(dayOffset(7)) }),
      makeInvoice({ id: 3, documentStatusId: SENT, dueDate: isoDate(dayOffset(-3)), paidDate: isoDate(dayOffset(-1)) }),
    ]
    const result = applyStatusFilter(invoices, 'overdue')
    expect(result.map(inv => inv.id)).toEqual([1])
  })

  it('keeps only accepted offers for the "accepted" filter', () => {
    const invoices = [
      makeInvoice({ id: 1, documentStatusId: ACCEPTED }),
      makeInvoice({ id: 2, documentStatusId: SENT }),
      makeInvoice({ id: 3, documentStatusId: REJECTED }),
    ]
    const result = applyStatusFilter(invoices, 'accepted')
    expect(result.map(inv => inv.id)).toEqual([1])
  })

  it('keeps only rejected offers for the "rejected" filter', () => {
    const invoices = [
      makeInvoice({ id: 1, documentStatusId: REJECTED }),
      makeInvoice({ id: 2, documentStatusId: SENT }),
      makeInvoice({ id: 3, documentStatusId: ACCEPTED }),
    ]
    const result = applyStatusFilter(invoices, 'rejected')
    expect(result.map(inv => inv.id)).toEqual([1])
  })

  it('keeps only expired offers for the "expired" filter', () => {
    const invoices = [
      makeInvoice({ id: 1, documentStatusId: SENT, offerValidUntil: isoDate(dayOffset(-3)) }),
      makeInvoice({ id: 2, documentStatusId: SENT, offerValidUntil: isoDate(dayOffset(7)) }),
      makeInvoice({ id: 3, documentStatusId: ACCEPTED, offerValidUntil: isoDate(dayOffset(-3)) }),
    ]
    const result = applyStatusFilter(invoices, 'expired')
    expect(result.map(inv => inv.id)).toEqual([1])
  })

  it('returns the input array unchanged for an unknown filter value', () => {
    const invoices = [makeInvoice({ id: 1 }), makeInvoice({ id: 2, documentStatusId: PAID })]
    expect(applyStatusFilter(invoices, 'mystery')).toBe(invoices)
  })
})

describe('useDocumentType — GoBD invoice flow', () => {
  const { statusOptions, statusText, statusClass, activeDocTypes, applyStatusFilter: filter } = useDocumentType()
  const INVOICE = 1
  const OFFER = 2
  const CREDIT_NOTE = 6

  it('offers no "Storniert" for an invoice draft (drafts are deleted, not cancelled)', () => {
    expect(statusOptions(INVOICE, DRAFT).map(o => o.value)).toEqual([DRAFT, SENT])
  })

  it('lets a partly paid invoice be paid in full or cancelled', () => {
    expect(statusOptions(INVOICE, PARTIALLY_PAID).map(o => o.value))
      .toEqual([PARTIALLY_PAID, PAID, CANCELLED])
  })

  it('never offers a status change for a credit note', () => {
    expect(statusOptions(CREDIT_NOTE, SENT).map(o => o.value)).toEqual([SENT])
  })

  it('labels the derived and credit-note statuses', () => {
    expect(statusText(INVOICE, PARTIALLY_PAID)).toBe('Teilweise bezahlt')
    expect(statusText(CREDIT_NOTE, SENT)).toBe('Ausgestellt')
    expect(statusText(INVOICE, SENT)).toBe('Gesendet')
    expect(statusClass(PARTIALLY_PAID)).toBe('partially-paid')
  })

  it('lists credit notes in the invoice tab only', () => {
    expect(activeDocTypes('invoice')).toEqual([INVOICE, CREDIT_NOTE])
    expect(activeDocTypes('offer')).toEqual([OFFER])
  })

  it('filters partly paid and cancelled invoices', () => {
    const invoices = [
      makeInvoice({ id: 1, documentStatusId: PARTIALLY_PAID }),
      makeInvoice({ id: 2, documentStatusId: CANCELLED }),
      makeInvoice({ id: 3, documentStatusId: SENT }),
    ]
    expect(filter(invoices, 'partiallyPaid').map(inv => inv.id)).toEqual([1])
    expect(filter(invoices, 'cancelled').map(inv => inv.id)).toEqual([2])
  })

  it('treats a partly paid invoice past its due date as overdue', () => {
    const invoices = [
      makeInvoice({ id: 1, documentStatusId: PARTIALLY_PAID, dueDate: isoDate(dayOffset(-2)) }),
    ]
    expect(filter(invoices, 'overdue').map(inv => inv.id)).toEqual([1])
  })
})

/**
 * Status parity: the buttons the SPA offers for an offer / cost calculation must be exactly the
 * transitions the backend accepts. Both sides read the same file; the Java side is
 * `OfferStatusTransitionsTest`. A mismatch here is what once made "Angenommen" answer HTTP 400.
 */
describe('useDocumentType — offer status parity with the backend', () => {
  // Vitest runs from frontend/ (jsdom gives import.meta.url an http: scheme, so no URL here).
  const fixture = JSON.parse(readFileSync(
    resolve(process.cwd(), '../backend/src/test/resources/status-parity/transitions.json'),
    'utf8'
  ))
  const { statusOptions } = useDocumentType()
  const allStatuses = [1, 2, 3, 4, 5, 6, 7, 8, 9]

  function backendTargets(from) {
    return fixture.transitions.filter(([f]) => f === from).map(([, to]) => to)
  }

  it.each(fixture.documentTypes)('offers exactly the allowed targets of type %i from every status', (typeId) => {
    for (const from of allStatuses) {
      const offered = statusOptions(typeId, from).map(o => o.value).filter(v => v !== from).sort()
      const expected = backendTargets(from).filter(to => !fixture.neverAPlainButton.includes(to)).sort()
      expect(offered, `type ${typeId} from ${from}`).toEqual(expected)
    }
  })

  it('offers every status as the first (current) entry, so the select can show it', () => {
    for (const from of [1, 2, 3, 4, 5, 8]) {
      expect(statusOptions(2, from)[0].value).toBe(from)
    }
  })

  it('lets a draft be sent or accepted, and a sent offer be accepted or rejected', () => {
    expect(statusOptions(2, DRAFT).map(o => o.value)).toEqual([DRAFT, SENT, ACCEPTED, CANCELLED])
    expect(statusOptions(2, SENT).map(o => o.value)).toEqual([SENT, ACCEPTED, REJECTED, CANCELLED])
  })

  it('offers nothing further after conversion or cancellation', () => {
    expect(statusOptions(2, 5).map(o => o.value)).toEqual([5])
    expect(statusOptions(3, CANCELLED).map(o => o.value)).toEqual([CANCELLED])
  })
})

describe('useDocumentType — titles', () => {
  const { newButtonLabel, documentFormTitle } = useDocumentType()

  it('puts the adjective first and matches it to the noun', () => {
    expect(newButtonLabel('invoice')).toBe('Neue Rechnung')
    expect(newButtonLabel('offer')).toBe('Neues Angebot')
    expect(newButtonLabel('costCalculation')).toBe('Neuer Kostenvoranschlag')
    expect(newButtonLabel('unknown')).toBe('Neue Rechnung')
  })

  it('has a "new" title for every document type', () => {
    for (const dt of DOCUMENT_TYPES) {
      expect(dt.newLabel).toMatch(new RegExp(`^Neu(e|er|es) ${dt.label}$`))
      expect(documentFormTitle(dt.id, false)).toBe(dt.newLabel)
      expect(documentFormTitle(dt.id, true)).toBe(`${dt.label} bearbeiten`)
    }
  })
})

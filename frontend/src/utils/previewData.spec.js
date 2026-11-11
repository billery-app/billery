import { describe, it, expect } from 'vitest'
import { buildPreviewContext, ownerVariables, formatVariables, totalsFromInvoice, SAMPLE_ITEMS } from './previewData.js'

describe('previewData', () => {
  it('uses the sample document without owner and live data', () => {
    const ctx = buildPreviewContext()
    expect(ctx.isSample).toBe(true)
    expect(ctx.items).toBe(SAMPLE_ITEMS)
    expect(ctx.variables.firma).toBe('Musterfirma GmbH')
    expect(ctx.variables.rechnungsdatum).toBe('15.01.2025')
    expect(ctx.variables.betrag).toBe('1.190,00 €')
    expect(ctx.variables).not.toHaveProperty('positionen')
  })

  it('owner data wins over samples, and empty owner fields stay empty like in the PDF', () => {
    const ctx = buildPreviewContext({ owner: { companyName: 'ACME', street: 'Weg', houseNumber: 1, postalCode: '1', city: 'X', phone: null } })
    expect(ctx.variables.firma).toBe('ACME')
    expect(ctx.variables.adresse).toBe('Weg 1, 1 X')
    expect(ctx.variables.telefon).toBe('')
  })

  it('live document data wins over everything', () => {
    const ctx = buildPreviewContext({
      owner: { companyName: 'ACME' },
      liveData: { variables: { kunde: 'Bob', rechnungsnummer: '' }, items: [], totals: { groups: [], net: 0, gross: 0 } },
      documentTypeId: 2
    })
    expect(ctx.isSample).toBe(false)
    expect(ctx.variables.kunde).toBe('Bob')
    expect(ctx.variables.rechnungsnummer).toBe('')
    expect(ctx.items).toEqual([])
    expect(ctx.documentTypeId).toBe(2)
  })

  it('formats dates, amounts and rates like the PDF', () => {
    expect(formatVariables({ rechnungsdatum: '2026-03-01', netto: 10, steuersatz: 19 }))
      .toEqual({ rechnungsdatum: '01.03.2026', netto: '10,00 €', steuersatz: '19,0' })
  })

  it('ownerVariables returns nothing without owner', () => {
    expect(ownerVariables(null)).toEqual({})
  })

  it('totalsFromInvoice uses the server VAT groups and the legal note', () => {
    expect(totalsFromInvoice({
      netAmount: 100, grossAmount: 100, taxTreatment: 'TAX_EXEMPT', taxExemptionReason: '§ 4 Nr. 21 UStG', vatGroups: []
    })).toEqual({ net: 100, gross: 100, groups: [], chargesVat: false, legalNote: 'Steuerfreie Leistung § 4 Nr. 21 UStG.' })
    const standard = totalsFromInvoice({ netAmount: '100.00', grossAmount: '119.00', vatGroups: [{ ratePercent: '19.00', net: '100.00', tax: '19.00' }] })
    expect(standard.chargesVat).toBe(true)
    expect(standard.groups).toEqual([{ ratePercent: 19, net: 100, tax: 19 }])
  })
})

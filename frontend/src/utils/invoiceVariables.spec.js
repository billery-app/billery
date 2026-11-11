import { describe, it, expect } from 'vitest'
import { buildPreviewVariables, buildLineItemsData } from './invoiceVariables.js'

const fullCustomer = {
  firstName: 'Anna',
  lastName: 'Müller',
  street: 'Beispielweg',
  houseNumber: 5,
  postalCode: '54321',
  city: 'Beispielstadt',
  email: 'anna@example.de',
  phone: '+49 987 654321'
}

const fullInvoice = {
  invoiceNumber: 'RE-2025-007',
  invoiceDate: '2025-01-15',
  dueDate: '2025-02-15',
  workStartDate: '2025-01-10',
  workEndDate: '2025-01-12',
  notes: 'Bitte überweisen Sie bis zum Fälligkeitsdatum.',
  netAmount: 1000,
  taxRate: 19,
  taxAmount: 190,
  grossAmount: 1190
}

describe('buildPreviewVariables - Real Workflows', () => {
  it('returns the { variables, totals } shape the editor expects', () => {
    const out = buildPreviewVariables({ invoice: fullInvoice, customer: fullCustomer })
    expect(out).toHaveProperty('variables')
    expect(out).toHaveProperty('totals')
  })

  describe('customer (receiver) - variables', () => {
    it('builds full name and address from a populated customer', () => {
      const { variables: v } = buildPreviewVariables({ invoice: fullInvoice, customer: fullCustomer })
      expect(v.kunde).toBe('Anna Müller')
      expect(v.kunde_strasse).toBe('Beispielweg 5')
      expect(v.kunde_plz_ort).toBe('54321 Beispielstadt')
      expect(v.kunde_email).toBe('anna@example.de')
      expect(v.kunde_telefon).toBe('+49 987 654321')
    })

    it('keeps the trailing space pattern when a name part is empty (mirrors backend)', () => {
      const { variables: v } = buildPreviewVariables({
        invoice: fullInvoice,
        customer: { ...fullCustomer, firstName: '', lastName: 'Müller' }
      })
      // Backend TemplateBasedPdfGenerator.substituteVariables does
      // firstName + " " + lastName without trimming. The editor preview
      // is the same so the user sees the real rendered output.
      expect(v.kunde).toBe(' Müller')
    })

    it('returns empty customer fields when the customer is missing', () => {
      const { variables: v } = buildPreviewVariables({ invoice: fullInvoice, customer: null })
      expect(v.kunde).toBe('')
      expect(v.kunde_strasse).toBe('')
      expect(v.kunde_plz_ort).toBe('')
      expect(v.kunde_email).toBe('')
      expect(v.kunde_telefon).toBe('')
    })

    it('handles null street and missing house number gracefully', () => {
      const { variables: v } = buildPreviewVariables({
        invoice: fullInvoice,
        customer: { ...fullCustomer, street: null, houseNumber: null }
      })
      expect(v.kunde_strasse).toBe(' ')
      expect(v.kunde_plz_ort).toBe('54321 Beispielstadt')
    })
  })

  describe('invoice header - variables', () => {
    it('formats ISO dates to the German short form', () => {
      const { variables: v } = buildPreviewVariables({ invoice: fullInvoice, customer: fullCustomer })
      expect(v.rechnungsdatum).toBe('15.01.2025')
      expect(v.invoice_date).toBe('15.01.2025')
      expect(v.faelligkeitsdatum).toBe('15.02.2025')
      expect(v.due_date).toBe('15.02.2025')
      expect(v.leistungsdatum).toBe('10.01.2025')
    })

    it('formats the work period as "start – end" when both dates are set', () => {
      const { variables: v } = buildPreviewVariables({ invoice: fullInvoice, customer: fullCustomer })
      expect(v.leistungszeitraum).toBe('10.01.2025 – 12.01.2025')
    })

    it('uses just the start date when end is missing', () => {
      const { variables: v } = buildPreviewVariables({
        invoice: { ...fullInvoice, workEndDate: '' },
        customer: fullCustomer
      })
      expect(v.leistungszeitraum).toBe('10.01.2025')
    })

    it('returns empty strings when the invoice is missing', () => {
      const { variables: v } = buildPreviewVariables({ invoice: null, customer: fullCustomer })
      expect(v.rechnungsnummer).toBe('')
      expect(v.rechnungsdatum).toBe('')
      expect(v.faelligkeitsdatum).toBe('')
      expect(v.leistungsdatum).toBe('')
      expect(v.leistungszeitraum).toBe('')
      expect(v.notizen).toBe('')
      expect(v.notes).toBe('')
    })

    it('passes malformed dates through unchanged so the preview is not blanked', () => {
      const { variables: v } = buildPreviewVariables({
        invoice: { ...fullInvoice, invoiceDate: 'not-a-date' },
        customer: fullCustomer
      })
      expect(v.rechnungsdatum).toBe('not-a-date')
    })
  })

  describe('totals - variables (substitution path) and totals (data-swap path)', () => {
    it('exposes both German and English aliases for every total in variables', () => {
      const { variables: v } = buildPreviewVariables({ invoice: fullInvoice, customer: fullCustomer })
      expect(v.netto).toBe(1000)
      expect(v.subtotal).toBe(1000)
      expect(v.steuersatz).toBe(19)
      expect(v.tax_rate).toBe(19)
      expect(v.steuer).toBe(190)
      expect(v.tax_amount).toBe(190)
      expect(v.betrag).toBe(1190)
      expect(v.gross_amount).toBe(1190)
      expect(v.waehrung).toBe('EUR')
      expect(v.currency).toBe('EUR')
    })

    it('returns empty string for missing totals in variables (SAMPLE_DATA does not leak)', () => {
      const { variables: v } = buildPreviewVariables({
        invoice: { ...fullInvoice, netAmount: null, taxAmount: null, grossAmount: null },
        customer: fullCustomer
      })
      expect(v.netto).toBe('')
      expect(v.steuer).toBe('')
      expect(v.betrag).toBe('')
    })

    it('exposes the totals block data shape in totals (for the render-time swap)', () => {
      const { totals: t } = buildPreviewVariables({ invoice: fullInvoice, customer: fullCustomer })
      expect(t).toMatchObject({
        subtotal: 1000,
        taxRate: 19,
        taxAmount: 190,
        total: 1190,
        currency: 'EUR',
        net: 1000,
        gross: 1190,
        chargesVat: true,
        legalNote: ''
      })
      expect(Array.isArray(t.groups)).toBe(true)
    })

    it('returns null values in totals for missing data (caller can detect absence)', () => {
      const { totals: t } = buildPreviewVariables({
        invoice: { ...fullInvoice, netAmount: null, taxRate: null, taxAmount: null, grossAmount: null },
        customer: fullCustomer
      })
      expect(t.subtotal).toBeNull()
      expect(t.taxRate).toBeNull()
      expect(t.taxAmount).toBeNull()
      expect(t.total).toBeNull()
      // currency is always 'EUR' (we never store anything else)
      expect(t.currency).toBe('EUR')
    })
  })

  describe('payment - variables', () => {
    it('sets verwendungszweck to the real invoice number', () => {
      const { variables: v } = buildPreviewVariables({ invoice: fullInvoice, customer: fullCustomer })
      expect(v.verwendungszweck).toBe('RE-2025-007')
    })

    it('does not set bank fields (they stay as SAMPLE_DATA fallbacks)', () => {
      const { variables: v } = buildPreviewVariables({ invoice: fullInvoice, customer: fullCustomer })
      expect(v).not.toHaveProperty('bank_name')
      expect(v).not.toHaveProperty('bic')
      expect(v).not.toHaveProperty('iban')
    })
  })

  describe('owner / sender (NOT touched by this helper)', () => {
    it('does not set any owner / sender keys', () => {
      const { variables: v } = buildPreviewVariables({ invoice: fullInvoice, customer: fullCustomer })
      expect(v).not.toHaveProperty('firma')
      expect(v).not.toHaveProperty('adresse')
      expect(v).not.toHaveProperty('telefon')
      expect(v).not.toHaveProperty('email')
      expect(v).not.toHaveProperty('ust_id')
    })
  })
})

describe('buildLineItemsData - Real Workflows', () => {
  it('maps a flat list of priced items to the line-items block shape', () => {
    const items = [
      {
        itemType: 'article',
        itemId: 1,
        itemName: 'Standardarbeit',
        itemDescription: '',
        quantity: 2,
        unitPrice: 50,
        totalPrice: 100,
        unit: 'Stk',
        parentItemId: null
      },
      {
        itemType: 'service',
        itemId: 2,
        itemName: 'Beratung',
        itemDescription: '',
        quantity: 1,
        unitPrice: 200,
        totalPrice: 200,
        unit: 'h',
        parentItemId: null
      }
    ]
    const out = buildLineItemsData(items)
    expect(out).toMatchObject([
      {
        description: 'Standardarbeit',
        quantity: 2,
        unit: 'Stk',
        rate: 50,
        amount: 100,
        parentItemId: null
      },
      {
        description: 'Beratung',
        quantity: 1,
        unit: 'h',
        rate: 200,
        amount: 200,
        parentItemId: null
      }
    ])
  })

  it('applies the CUSTOM invariant: qty=1, rate=0, amount=0, unit=""', () => {
    const items = [
      {
        itemType: 'custom',
        itemId: 0,
        itemName: 'Anfahrt',
        itemDescription: 'pauschal',
        quantity: 99,
        unitPrice: 99,
        totalPrice: 99,
        unit: 'km',
        parentItemId: null
      }
    ]
    const out = buildLineItemsData(items)
    expect(out[0].description).toBe('Anfahrt')
    expect(out[0].quantity).toBe(1)
    expect(out[0].unit).toBe('')
    expect(out[0].rate).toBe(0)
    expect(out[0].amount).toBe(0)
  })

  it('preserves the sub-item tree', () => {
    const items = [
      {
        itemType: 'article',
        itemId: 1,
        itemName: 'Hauptposition',
        quantity: 1,
        unitPrice: 100,
        totalPrice: 100,
        unit: 'Stk',
        parentItemId: null,
        children: [
          {
            itemType: 'service',
            itemId: 2,
            itemName: 'Unterposition',
            quantity: 2,
            unitPrice: 25,
            totalPrice: 50,
            unit: 'h',
            parentItemId: 1
          }
        ]
      }
    ]
    const out = buildLineItemsData(items)
    expect(out).toHaveLength(1)
    expect(out[0].description).toBe('Hauptposition')
    expect(out[0].children).toMatchObject([
      {
        description: 'Unterposition',
        quantity: 2,
        unit: 'h',
        rate: 25,
        amount: 50,
        parentItemId: 1
      }
    ])
  })

  it('carries the VAT rate per line and the text of note rows', () => {
    const out = buildLineItemsData([
      { itemType: 'article', itemName: 'A', quantity: 1, unitPrice: 10, totalPrice: 10, taxRate: 7 },
      { itemType: 'CUSTOM', itemName: 'Hinweis', itemDescription: 'Text' }
    ])
    expect(out[0]).toMatchObject({ taxRate: 7, isNote: false })
    expect(out[1]).toMatchObject({ isNote: true, noteText: 'Text', taxRate: null })
  })

  it('returns an empty array for null or non-array input', () => {
    expect(buildLineItemsData(null)).toEqual([])
    expect(buildLineItemsData(undefined)).toEqual([])
    expect(buildLineItemsData('not an array')).toEqual([])
  })

  it('treats a parentItemId of 0 as null (no parent)', () => {
    const out = buildLineItemsData([
      {
        itemType: 'article',
        itemId: 1,
        itemName: 'Root',
        quantity: 1,
        unitPrice: 50,
        totalPrice: 50,
        unit: 'Stk',
        parentItemId: 0
      }
    ])
    expect(out[0].parentItemId).toBeNull()
  })
})

import { describe, it, expect } from 'vitest'
import {
  BLOCK_TYPES, getBlockByType, getFormat, safeColor, formatCurrency, numberLabel, blockDisplayName
} from './blockRegistry.js'
import { isKnownVariable } from './variableRegistry.js'
import { variablesIn } from './variableText.js'

const render = (type, data, config, ctx) => {
  const blockType = getBlockByType(type)
  return blockType.render({ ...blockType.defaultData, ...data }, { ...blockType.defaultConfig, ...config }, ctx)
}

describe('blockRegistry', () => {
  describe('schema', () => {
    it('contains the eight block types the PDF renderer knows', () => {
      expect(BLOCK_TYPES.map(b => b.type).sort()).toEqual(
        ['address', 'footer', 'header', 'invoice-meta', 'line-items', 'notes', 'payment', 'totals']
      )
    })

    it('describes every block completely', () => {
      for (const type of BLOCK_TYPES) {
        expect(type.name, type.type).toBeTruthy()
        expect(type.description, type.type).toBeTruthy()
        expect(type.icon, type.type).toBeTruthy()
        expect(typeof type.render).toBe('function')
        expect(Array.isArray(type.fields)).toBe(true)
        expect(Array.isArray(type.settings)).toBe(true)
        expect(type.limits).toBeDefined()
      }
    })

    it('has a field definition for every default data key (except the address role preset)', () => {
      for (const type of BLOCK_TYPES) {
        const fieldKeys = type.fields.map(f => f.key)
        for (const key of Object.keys(type.defaultData)) {
          expect(fieldKeys, `${type.type}.${key}`).toContain(key)
        }
      }
    })

    it('has a setting definition for every default config key except format', () => {
      for (const type of BLOCK_TYPES) {
        const settingKeys = type.settings.map(s => s.key)
        for (const key of Object.keys(type.defaultConfig).filter(k => k !== 'format')) {
          expect(settingKeys, `${type.type}.${key}`).toContain(key)
        }
      }
    })

    it('never stores sample literals: default texts only use known variables', () => {
      for (const type of BLOCK_TYPES) {
        const presets = Object.values(type.rolePresets || {}).map(p => p.data)
        for (const data of [type.defaultData, ...presets]) {
          for (const value of Object.values(data)) {
            if (typeof value !== 'string') continue
            for (const name of variablesIn(value)) expect(isKnownVariable(name), name).toBe(true)
          }
        }
      }
      expect(getBlockByType('invoice-meta').defaultData.invoiceId).toBe('{{rechnungsnummer}}')
      expect(getBlockByType('header').defaultData.companyName).toBe('{{firma}}')
    })

    it('requires invoice-meta, line-items and totals exactly once', () => {
      for (const type of ['invoice-meta', 'line-items', 'totals']) {
        expect(getBlockByType(type).limits).toMatchObject({ max: 1, required: true })
      }
    })
  })

  describe('helpers', () => {
    it('getFormat uses block defaults and rejects unsafe values', () => {
      expect(getFormat({}, { fontSize: 21, fontColor: '#2563eb' })).toEqual({
        fontFamily: 'Inter', fontSize: 21, fontColor: '#2563eb', fontWeight: 'normal'
      })
      const fmt = getFormat({ format: { fontColor: 'red;x', fontFamily: 'A";}', fontSize: 'big', fontWeight: 'bold' } })
      expect(fmt).toEqual({ fontFamily: 'Inter', fontSize: 10.5, fontColor: '#1f2937', fontWeight: 'bold' })
    })

    it('safeColor accepts hex colors only', () => {
      expect(safeColor('#abc', '#000')).toBe('#abc')
      expect(safeColor('url(x)', '#000')).toBe('#000')
    })

    it('formats amounts like the PDF', () => {
      expect(formatCurrency(1234.5)).toBe('1.234,50 €')
      expect(formatCurrency('x')).toBe('0,00 €')
    })

    it('labels the document number per document type', () => {
      expect(numberLabel(1)).toBe('Rechnungsnummer')
      expect(numberLabel(2)).toBe('Angebotsnummer')
      expect(numberLabel(6)).toBe('Stornorechnungsnummer')
    })

    it('names address blocks by role', () => {
      expect(blockDisplayName({ type: 'address', data: { role: 'receiver' } })).toBe('Adresse – Empfänger')
      expect(blockDisplayName({ type: 'totals', data: {} })).toBe('Summen')
    })
  })

  describe('render', () => {
    it('header uses tables instead of flexbox (the PDF renderer has no flexbox)', () => {
      const html = render('header', { companyName: 'ACME', logoUrl: '/uploads/l.png' }, { logoAlignment: 'right' })
      expect(html).toContain('<table')
      expect(html).not.toContain('display: flex')
      expect(html).toContain('ACME')
    })

    it('address labels follow the configured text and are escaped', () => {
      const html = render('address', { name: 'Anna' }, { showLabels: true, label: '<b>Kunde</b>' })
      expect(html).toContain('&lt;b&gt;Kunde&lt;/b&gt;')
      expect(html).toContain('<strong>Anna</strong>')
    })

    it('invoice-meta falls back to the document data for empty fields', () => {
      const html = render('invoice-meta', { invoiceId: '', invoiceDate: '', dueDate: '' }, {}, {
        documentTypeId: 2,
        variables: { rechnungsnummer: 'AN-7', rechnungsdatum: '01.02.2026', faelligkeitsdatum: '15.02.2026' }
      })
      expect(html).toContain('Angebotsnummer:</strong> AN-7')
      expect(html).toContain('01.02.2026')
      expect(html).toContain('15.02.2026')
    })

    it('invoice-meta shows no due date on a credit note', () => {
      const html = render('invoice-meta', { dueDate: 'x' }, {}, { documentTypeId: 6, variables: {} })
      expect(html).not.toContain('Fällig am')
    })

    it('line-items renders items, note rows and sub-items', () => {
      const html = render('line-items', {}, {}, {
        items: [{
          description: 'Arbeit', quantity: 1.5, unit: 'h', rate: 80, amount: 120, taxRate: 19,
          children: [{ description: 'Hinweis', isNote: true, noteText: 'Details <i>' }]
        }]
      })
      expect(html).toContain('Arbeit')
      expect(html).toContain('1,5')
      expect(html).toContain('120,00 €')
      expect(html).toContain('colspan=')
      expect(html).toContain('Details &lt;i&gt;')
    })

    it('line-items adds a VAT column for mixed rates', () => {
      const html = render('line-items', {}, {}, {
        items: [
          { description: 'A', quantity: 1, rate: 1, amount: 1, taxRate: 19 },
          { description: 'B', quantity: 1, rate: 1, amount: 1, taxRate: 7 }
        ]
      })
      expect(html).toContain('USt.')
      expect(html).toContain('7 %')
    })

    it('totals shows one tax row per VAT group', () => {
      const html = render('totals', {}, {}, {
        totals: {
          net: 200, gross: 226, chargesVat: true, legalNote: '',
          groups: [{ ratePercent: 19, net: 100, tax: 19 }, { ratePercent: 7, net: 100, tax: 7 }]
        }
      })
      expect(html).toContain('MwSt. 19 % auf 100,00 €')
      expect(html).toContain('MwSt. 7 % auf 100,00 €')
      expect(html).toContain('226,00 €')
    })

    it('totals prints the legal note instead of tax for small businesses', () => {
      const html = render('totals', {}, {}, {
        totals: { net: 100, gross: 100, chargesVat: false, legalNote: 'Gemäß § 19 UStG ...', groups: [{ ratePercent: 0, net: 100, tax: 0 }] }
      })
      expect(html).not.toContain('MwSt.')
      expect(html).toContain('Gemäß § 19 UStG')
    })

    it('notes renders nothing without text and sanitizes colors', () => {
      expect(render('notes', { content: '  ' }, {})).toBe('')
      const html = render('notes', { content: 'Hallo' }, { backgroundColor: 'red;x' })
      expect(html).toContain('#fef3c7')
    })

    it('payment and footer render only filled fields', () => {
      const payment = render('payment', { paymentMethod: '', iban: 'DE00', bic: 'X' }, { showBic: false })
      expect(payment).toContain('DE00')
      expect(payment).not.toContain('BIC')
      expect(payment).not.toContain('Zahlungsart')
      const footer = render('footer', { text: 'Danke', website: '', taxNumber: '12/345' }, {})
      expect(footer).toContain('Steuernummer: 12/345')
      expect(footer).not.toContain('Registernummer')
    })
  })
})

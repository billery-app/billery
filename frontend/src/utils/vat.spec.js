import { describe, it, expect } from 'vitest'
import { calculateVat, legalNote, chargesVat, formatRate } from './vat.js'

const line = (quantity, unitPrice, taxRate, children = []) =>
  ({ itemType: 'article', quantity, unitPrice, taxRate, children })

describe('calculateVat', () => {
  it('groups by rate and sums net, tax and gross', () => {
    const vat = calculateVat([line(1.5, 100, 19), line(2, 25, 7)], 'STANDARD')

    expect(vat.groups).toEqual([
      { rate: 7, net: 50, tax: 3.5 },
      { rate: 19, net: 150, tax: 28.5 }
    ])
    expect(vat).toMatchObject({ net: 200, tax: 32, gross: 232 })
  })

  it('rounds once per rate group, like the backend', () => {
    const vat = calculateVat([line(1, 0.03, 19), line(1, 0.03, 19), line(1, 0.03, 19)], 'STANDARD')
    expect(vat.tax).toBe(0.02)
  })

  it('includes sub-items and ignores free-text notes', () => {
    const note = { itemType: 'custom', quantity: 1, unitPrice: 99 }
    const vat = calculateVat([line(1, 100, 19, [line(2, 10, 7), note])], 'STANDARD')
    expect(vat.net).toBe(120)
    expect(vat.tax).toBe(20.4)
  })

  it('charges no VAT for the other treatments', () => {
    for (const treatment of ['SMALL_BUSINESS', 'REVERSE_CHARGE', 'TAX_EXEMPT']) {
      const vat = calculateVat([line(1, 100, 19)], treatment)
      expect(vat.groups).toEqual([{ rate: 0, net: 100, tax: 0 }])
      expect(vat.gross).toBe(100)
    }
  })

  it('defaults a missing line rate to 19 %', () => {
    expect(calculateVat([{ itemType: 'service', quantity: 1, unitPrice: 100 }], 'STANDARD').tax).toBe(19)
  })
})

describe('legal notes', () => {
  it('names the legal basis of each treatment without VAT', () => {
    expect(legalNote('STANDARD')).toBe('')
    expect(legalNote('SMALL_BUSINESS')).toContain('§ 19 UStG')
    expect(legalNote('REVERSE_CHARGE')).toContain('§ 13b UStG')
    expect(legalNote('TAX_EXEMPT', 'gemäß § 4 Nr. 14 UStG')).toBe('Steuerfreie Leistung gemäß § 4 Nr. 14 UStG.')
  })

  it('treats a missing treatment as regular taxation', () => {
    expect(chargesVat(undefined)).toBe(true)
    expect(chargesVat('REVERSE_CHARGE')).toBe(false)
    expect(formatRate(7)).toBe('7 %')
  })
})

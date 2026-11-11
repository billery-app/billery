import { describe, expect, it } from 'vitest'
import { describeAmortisation } from './equipmentAmortisation.js'

const row = fields => ({ id: 1, purchaseValue: 1000, totalRevenue: 0, usageCount: 0, paybackPercent: 0, ...fields })

describe('describeAmortisation', () => {
  it('shows the percentage and a proportional bar below the purchase price', () => {
    const shown = describeAmortisation(row({ totalRevenue: 560.5, usageCount: 4, paybackPercent: 56.1 }))

    expect(shown).toMatchObject({ hasPurchaseValue: true, done: false, barWidth: 56.1, revenue: 560.5, usageCount: 4 })
    expect(shown.percentText).toBe('56,1 %')
  })

  it('caps bar and percentage at 100 once the purchase price is earned', () => {
    const shown = describeAmortisation(row({ purchaseValue: 100, totalRevenue: 250, paybackPercent: 250 }))

    expect(shown.done).toBe(true)
    expect(shown.barWidth).toBe(100)
    expect(shown.percentText).toBe('100 %')
  })

  it('is done exactly at the purchase price', () => {
    expect(describeAmortisation(row({ totalRevenue: 1000, paybackPercent: 100 })).done).toBe(true)
  })

  it('does not round a nearly earned price up to done', () => {
    const shown = describeAmortisation(row({ totalRevenue: 999.6, paybackPercent: 100 }))

    expect(shown.done).toBe(false)
    expect(shown.percentText).toBe('99,9 %')
    expect(shown.barWidth).toBe(99.9)
  })

  it('has nothing to amortise without a purchase price', () => {
    for (const purchaseValue of [0, null, undefined]) {
      const shown = describeAmortisation(row({ purchaseValue, totalRevenue: 70, usageCount: 1 }))
      expect(shown).toMatchObject({ hasPurchaseValue: false, done: false, revenue: 70, usageCount: 1 })
    }
  })

  it('reads amounts the API sends as strings and survives missing fields', () => {
    expect(describeAmortisation({ purchaseValue: '100.00', totalRevenue: '100.00', paybackPercent: '100.0' }).done).toBe(true)
    expect(describeAmortisation({})).toMatchObject({ hasPurchaseValue: false, barWidth: 0, revenue: 0, usageCount: 0 })
    expect(describeAmortisation(undefined)).toBeNull()
  })
})

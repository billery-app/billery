import { describe, it, expect } from 'vitest'
import { splitStreetAndNumber } from './addressFields.js'

describe('splitStreetAndNumber', () => {
  it('moves a trailing number into an empty house number', () => {
    const form = { street: 'Musterstraße 12', houseNumber: null }
    expect(splitStreetAndNumber(form)).toBe(true)
    expect(form).toEqual({ street: 'Musterstraße', houseNumber: 12 })
  })

  it('keeps a house number that is already there', () => {
    const form = { street: 'Musterstraße 12', houseNumber: 7 }
    expect(splitStreetAndNumber(form)).toBe(false)
    expect(form).toEqual({ street: 'Musterstraße 12', houseNumber: 7 })
  })

  it('leaves streets without a trailing whole number alone', () => {
    for (const street of ['Am Markt', 'Musterstraße 12a', 'Straße des 17. Juni', '']) {
      const form = { street, houseNumber: null }
      expect(splitStreetAndNumber(form)).toBe(false)
      expect(form.street).toBe(street)
    }
  })
})

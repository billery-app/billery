import { describe, expect, it } from 'vitest'
import { numberParam, param } from './route.js'

describe('route parameters', () => {
  it('reads a single value, the first of a repeated key, or nothing', () => {
    expect(param('offer')).toBe('offer')
    expect(param(['invoice', 'offer'])).toBe('invoice')
    expect(param(undefined)).toBe('')
    expect(param(null)).toBe('')
    expect(param([])).toBe('')
  })

  it('reads numbers and refuses what is not one', () => {
    expect(numberParam('42')).toBe(42)
    expect(numberParam(['7', '8'])).toBe(7)
    expect(numberParam('abc')).toBeNull()
    expect(numberParam(undefined)).toBeNull()
  })
})

import { describe, it, expect } from 'vitest'
import { generateId, formatCurrency, formatDate, debounce, deepClone } from './helpers.js'

describe('helpers - Real Workflows', () => {
  describe('generateId', () => {
    it('generates unique IDs', () => {
      const id1 = generateId()
      const id2 = generateId()

      expect(id1).not.toBe(id2)
    })

    it('generates string IDs', () => {
      const id = generateId()
      expect(typeof id).toBe('string')
    })

    it('generates ID with id_ prefix', () => {
      const id = generateId()
      expect(id.startsWith('id_')).toBe(true)
    })

    it('generates different IDs on consecutive calls', () => {
      const ids = new Set()
      for (let i = 0; i < 100; i++) {
        ids.add(generateId())
      }
      expect(ids.size).toBe(100)
    })
  })

  describe('formatCurrency', () => {
    it('formats EUR by default', () => {
      const result = formatCurrency(100)
      expect(result).toContain('100')
      expect(result).toContain('€')
    })

    it('formats with custom currency', () => {
      const result = formatCurrency(100, 'USD')
      expect(result).toContain('100')
      expect(result).toContain('$')
    })

    it('formats with custom locale', () => {
      const result = formatCurrency(1234.56, 'EUR', 'de-DE')
      expect(result).toContain('1.234,56')
    })

    it('handles zero amount', () => {
      const result = formatCurrency(0)
      expect(result).toContain('0')
    })

    it('handles negative amount', () => {
      const result = formatCurrency(-50)
      expect(result).toContain('-')
    })

    it('handles decimal amounts', () => {
      const result = formatCurrency(99.99)
      expect(result).toContain('99,99')
    })
  })

  describe('formatDate', () => {
    it('formats date with DD.MM.YYYY format', () => {
      const result = formatDate('2026-03-15', 'DD.MM.YYYY')
      expect(result).toContain('15')
      expect(result).toContain('03')
      expect(result).toContain('2026')
    })

    it('formats date with YYYY-MM-DD format', () => {
      const result = formatDate('2026-03-15', 'YYYY-MM-DD')
      expect(result).toBe('2026-03-15')
    })

    it('returns empty string for empty date', () => {
      expect(formatDate('')).toBe('')
    })

    it('returns empty string for null', () => {
      expect(formatDate(null)).toBe('')
    })

    it('returns empty string for undefined', () => {
      expect(formatDate(undefined)).toBe('')
    })

    it('handles different date inputs', () => {
      const result = formatDate('2025-01-01', 'DD.MM.YYYY')
      expect(result).toContain('01')
    })

    it('uses default German locale', () => {
      const result = formatDate('2026-12-25')
      expect(result).toContain('25.12.')
    })
  })

  describe('debounce', () => {
    beforeEach(() => {
      vi.useFakeTimers()
    })

    afterEach(() => {
      vi.useRealTimers()
    })

    it('delays function execution', async () => {
      const fn = vi.fn()
      const debouncedFn = debounce(fn, 300)

      debouncedFn()
      expect(fn).not.toHaveBeenCalled()

      vi.advanceTimersByTime(300)
      expect(fn).toHaveBeenCalledTimes(1)
    })

    it('resets timer on subsequent calls', async () => {
      const fn = vi.fn()
      const debouncedFn = debounce(fn, 300)

      debouncedFn()
      vi.advanceTimersByTime(200)
      debouncedFn()
      vi.advanceTimersByTime(200)

      expect(fn).not.toHaveBeenCalled()

      vi.advanceTimersByTime(100)
      expect(fn).toHaveBeenCalledTimes(1)
    })

    it('passes arguments to function', async () => {
      const fn = vi.fn()
      const debouncedFn = debounce(fn, 300)

      debouncedFn('arg1', 'arg2')
      vi.advanceTimersByTime(300)

      expect(fn).toHaveBeenCalledWith('arg1', 'arg2')
    })

    it('executes only once after multiple rapid calls', async () => {
      const fn = vi.fn()
      const debouncedFn = debounce(fn, 300)

      for (let i = 0; i < 10; i++) {
        debouncedFn()
      }

      vi.advanceTimersByTime(300)
      expect(fn).toHaveBeenCalledTimes(1)
    })
  })

  describe('deepClone', () => {
    it('clones simple object', () => {
      const original = { a: 1, b: 2 }
      const cloned = deepClone(original)

      expect(cloned).toEqual(original)
      expect(cloned).not.toBe(original)
    })

    it('clones nested object', () => {
      const original = { a: { b: { c: 1 } } }
      const cloned = deepClone(original)

      expect(cloned.a.b.c).toBe(1)
      expect(cloned).not.toBe(original)
      expect(cloned.a).not.toBe(original.a)
    })

    it('clones array', () => {
      const original = [1, 2, [3, 4]]
      const cloned = deepClone(original)

      expect(cloned).toEqual(original)
      expect(cloned).not.toBe(original)
      expect(cloned[2]).not.toBe(original[2])
    })

    it('clones primitive values', () => {
      expect(deepClone(123)).toBe(123)
      expect(deepClone('string')).toBe('string')
      expect(deepClone(true)).toBe(true)
      expect(deepClone(null)).toBe(null)
    })

    it('handles empty object', () => {
      const original = {}
      const cloned = deepClone(original)

      expect(cloned).toEqual({})
      expect(cloned).not.toBe(original)
    })

    it('handles empty array', () => {
      const original = []
      const cloned = deepClone(original)

      expect(cloned).toEqual([])
      expect(cloned).not.toBe(original)
    })

    it('handles Date values', () => {
      const original = { date: '2026-01-01T00:00:00.000Z' }
      const cloned = deepClone(original)

      expect(cloned.date).toBe(original.date)
    })

    it('preserves arrays in objects', () => {
      const original = { items: [1, 2, 3] }
      const cloned = deepClone(original)

      expect(cloned.items).toEqual([1, 2, 3])
      expect(cloned.items).not.toBe(original.items)
    })
  })
})
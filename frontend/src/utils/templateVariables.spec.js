import { describe, it, expect } from 'vitest'
import {
  VARIABLE_CATEGORIES,
  SPECIAL_VARIABLES,
  getAllVariables,
  getVariablesByCategory,
  isValidVariable,
  extractVariablesFromText
} from './templateVariables.js'

describe('templateVariables - Real Workflows', () => {
  describe('VARIABLE_CATEGORIES', () => {
    it('contains absender category', () => {
      const category = VARIABLE_CATEGORIES.find(c => c.name === 'absender')
      expect(category).toBeDefined()
      expect(category.label).toBe('Absender')
      expect(category.variables.length).toBeGreaterThan(0)
    })

    it('contains bank category', () => {
      const category = VARIABLE_CATEGORIES.find(c => c.name === 'bank')
      expect(category).toBeDefined()
      expect(category.label).toBe('Bankdaten')
    })

    it('contains kunde category', () => {
      const category = VARIABLE_CATEGORIES.find(c => c.name === 'kunde')
      expect(category).toBeDefined()
      expect(category.label).toBe('Empfänger')
    })

    it('contains rechnung category', () => {
      const category = VARIABLE_CATEGORIES.find(c => c.name === 'rechnung')
      expect(category).toBeDefined()
      expect(category.label).toBe('Rechnung')
    })

    it('contains layout category', () => {
      const category = VARIABLE_CATEGORIES.find(c => c.name === 'layout')
      expect(category).toBeDefined()
      expect(category.label).toBe('Layout')
    })
  })

  describe('Variable Structure', () => {
    it('each variable has name property', () => {
      const allVars = getAllVariables()
      allVars.forEach(v => {
        expect(v.name).toBeDefined()
        expect(v.name).toContain('{{')
        expect(v.name).toContain('}}')
      })
    })

    it('each variable has label property', () => {
      const allVars = getAllVariables()
      allVars.forEach(v => {
        expect(v.label).toBeDefined()
        expect(typeof v.label).toBe('string')
      })
    })

    it('each variable has description property', () => {
      const allVars = getAllVariables()
      allVars.forEach(v => {
        expect(v.description).toBeDefined()
      })
    })
  })

  describe('getAllVariables', () => {
    it('returns all variables from all categories', () => {
      const allVars = getAllVariables()
      expect(allVars.length).toBeGreaterThan(10)
    })

    it('returns flat array of variables', () => {
      const allVars = getAllVariables()
      expect(Array.isArray(allVars)).toBe(true)
      expect(allVars[0].name).toBeDefined()
    })
  })

  describe('getVariablesByCategory', () => {
    it('returns variables for absender category', () => {
      const vars = getVariablesByCategory('absender')
      expect(vars.length).toBeGreaterThan(0)
      expect(vars[0].name).toContain('OWNER')
    })

    it('returns variables for bank category', () => {
      const vars = getVariablesByCategory('bank')
      expect(vars.length).toBeGreaterThan(0)
      expect(vars[0].name).toContain('IBAN')
    })

    it('returns empty array for unknown category', () => {
      const vars = getVariablesByCategory('unknown')
      expect(vars).toHaveLength(0)
    })
  })

  describe('isValidVariable', () => {
    it('returns true for valid variable', () => {
      expect(isValidVariable('{{OWNER_COMPANY}}')).toBe(true)
    })

    it('returns true for special variable pageNumber', () => {
      expect(isValidVariable('{{pageNumber}}')).toBe(true)
    })

    it('returns true for special variable totalPages', () => {
      expect(isValidVariable('{{totalPages}}')).toBe(true)
    })

    it('returns false for invalid variable', () => {
      expect(isValidVariable('{{UNKNOWN_VAR}}')).toBe(false)
    })

    it('returns false for empty string', () => {
      expect(isValidVariable('')).toBe(false)
    })
  })

  describe('extractVariablesFromText', () => {
    it('extracts single valid variable', () => {
      const text = 'Invoice: {{INVOICE_NUMBER}}'
      const vars = extractVariablesFromText(text)

      expect(vars).toContain('{{INVOICE_NUMBER}}')
    })

    it('extracts multiple valid variables', () => {
      const text = '{{OWNER_COMPANY}} and {{INVOICE_NUMBER}}'
      const vars = extractVariablesFromText(text)

      expect(vars).toContain('{{OWNER_COMPANY}}')
      expect(vars).toContain('{{INVOICE_NUMBER}}')
    })

    it('removes duplicates', () => {
      const text = '{{OWNER_COMPANY}} and {{OWNER_COMPANY}} again'
      const vars = extractVariablesFromText(text)

      const count = vars.filter(v => v === '{{OWNER_COMPANY}}').length
      expect(count).toBe(1)
    })

    it('returns empty array for empty text', () => {
      expect(extractVariablesFromText('')).toHaveLength(0)
    })

    it('returns empty array for null', () => {
      expect(extractVariablesFromText(null)).toHaveLength(0)
    })

    it('filters out invalid variables', () => {
      const text = '{{OWNER_COMPANY}} {{unknown_var}}'
      const vars = extractVariablesFromText(text)

      expect(vars).toContain('{{OWNER_COMPANY}}')
      expect(vars).not.toContain('{{unknown_var}}')
    })
  })

  describe('SPECIAL_VARIABLES', () => {
    it('defines pageNumber', () => {
      expect(SPECIAL_VARIABLES.pageNumber).toBe('{{pageNumber}}')
    })

    it('defines totalPages', () => {
      expect(SPECIAL_VARIABLES.totalPages).toBe('{{totalPages}}')
    })
  })
})